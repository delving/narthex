package services

import play.api.Logging
import play.api.libs.json._
import play.api.libs.ws.{WSClient, WSResponse}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

case class IndexJobRequest(mode: String, files: Seq[String], deletedIds: Option[String], docType: String, docTags: String, agent: String, force: Boolean)
case class IndexTargetState(cursor: Long, state: String, error: Option[String])
case class IndexJobStatus(jobId: String, state: String, seq: Option[Long], error: Option[String],
                           counts: Map[String, Long], targets: Map[String, IndexTargetState])

sealed trait IndexServiceError { def message: String }
case class IndexServiceUnreachable(message: String) extends IndexServiceError
/** 4xx: config or request error, never retried; code "bad_response" = 2xx with an unusable body */
case class IndexServiceRejected(status: Int, code: String, message: String) extends IndexServiceError

trait IndexServiceApi {
  def postJob(org: String, spec: String, req: IndexJobRequest): Future[Either[IndexServiceError, String]]
  def jobStatus(org: String, spec: String, jobId: String): Future[Either[IndexServiceError, IndexJobStatus]]
  /** Take the dataset out of search and OAI-PMH (the store is kept); Right(seq). A 409 dataset_locked is IndexServiceRejected. */
  def depublish(org: String, spec: String, by: String): Future[Either[IndexServiceError, Long]]
  /** Inverse of depublish. */
  def publish(org: String, spec: String, by: String): Future[Either[IndexServiceError, Long]]
}

object IndexVisibility {
  /** Depublish (published = false) or publish, retrying a 409 dataset_locked (an index service job is running) after each wait. */
  def set(api: IndexServiceApi, org: String, spec: String, by: String, published: Boolean,
          sleep: Long => Unit = Thread.sleep(_), backoffMs: Seq[Long] = Seq(2000L, 5000L, 10000L),
          patience: scala.concurrent.duration.FiniteDuration = scala.concurrent.duration.DurationInt(2).minutes): Either[IndexServiceError, Long] = {
    def once = scala.util.Try(scala.concurrent.Await.result(
      if (published) api.publish(org, spec, by) else api.depublish(org, spec, by), patience))
      .fold(e => Left(IndexServiceUnreachable(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))), identity)
    def attempt(waits: Seq[Long]): Either[IndexServiceError, Long] = once match {
      case Left(IndexServiceRejected(409, "dataset_locked", _)) if waits.nonEmpty => sleep(waits.head); attempt(waits.tail)
      case other => other
    }
    attempt(backoffMs)
  }

  def describe(e: IndexServiceError, published: Boolean): String =
    s"index service ${if (published) "publish" else "depublish"} failed: ${e match {
      case r: IndexServiceRejected => s"HTTP ${r.status} ${r.code}: ${r.message}"
      case other => other.message
    }}"
}

/** @param backoffMs waits between attempts; its length + 1 is the number of tries (default 3 tries, 1 s / 2 s) */
class IndexServiceClient(ws: WSClient, baseUrl: String, token: String, backoffMs: Seq[Int] = Seq(1000, 2000))
                  (implicit ec: ExecutionContext) extends IndexServiceApi with Logging {

  private val base = baseUrl.replaceAll("/+$", "")
  private def jobsUrl(org: String, spec: String) = s"$base/datasets/$org/$spec/jobs"

  def postJob(org: String, spec: String, req: IndexJobRequest): Future[Either[IndexServiceError, String]] = {
    val body = Json.obj(
      "kind" -> "narthex-processed",
      "mode" -> req.mode,
      "files" -> req.files,
      "deletedIds" -> req.deletedIds,
      "doc" -> Json.obj("type" -> req.docType, "tags" -> req.docTags),
      "agent" -> req.agent,
      "force" -> req.force
    )
    send(() => ws.url(jobsUrl(org, spec)).withHttpHeaders(headers: _*).post(body)).map(_.flatMap { r =>
      scala.util.Try(r.json).toOption.flatMap(j => (j \ "jobId").asOpt[String])
        .toRight[IndexServiceError](IndexServiceRejected(r.status, "bad_response", s"no jobId in response: ${r.body.take(200)}"))
    })
  }

  def jobStatus(org: String, spec: String, jobId: String): Future[Either[IndexServiceError, IndexJobStatus]] =
    send(() => ws.url(s"${jobsUrl(org, spec)}/$jobId").withHttpHeaders(headers: _*).get()).map(_.flatMap { r =>
      scala.util.Try(r.json).toOption.flatMap(parseStatus)
        .toRight[IndexServiceError](IndexServiceRejected(r.status, "bad_response", s"unreadable job status: ${r.body.take(200)}"))
    })

  private def visibility(org: String, spec: String, by: String, action: String): Future[Either[IndexServiceError, Long]] =
    send(() => ws.url(s"$base/datasets/$org/$spec/$action").withHttpHeaders(headers: _*).post(Json.obj("by" -> by))).map(_.flatMap { r =>
      scala.util.Try(r.json).toOption.flatMap(j => (j \ "seq").asOpt[Long])
        .toRight[IndexServiceError](IndexServiceRejected(r.status, "bad_response", s"no seq in response: ${r.body.take(200)}"))
    })

  def depublish(org: String, spec: String, by: String) = visibility(org, spec, by, "depublish")
  def publish(org: String, spec: String, by: String) = visibility(org, spec, by, "publish")

  private def headers = Seq("Authorization" -> s"Bearer $token", "Content-Type" -> "application/json", "Accept" -> "application/json")

  private def parseStatus(j: JsValue): Option[IndexJobStatus] = for {
    id <- (j \ "jobId").asOpt[String]
    state <- (j \ "state").asOpt[String]
  } yield IndexJobStatus(
    id, state,
    (j \ "seq").asOpt[Long],
    (j \ "error").asOpt[String],
    (j \ "ingest" \ "counts").asOpt[Map[String, Long]].getOrElse(Map.empty),
    (j \ "index").asOpt[JsObject].map(_.fields.flatMap { case (t, v) =>
      (v \ "cursor").asOpt[Long].orElse(Some(0L)).map(c =>
        t -> IndexTargetState(c, (v \ "state").asOpt[String].getOrElse(""), (v \ "error").asOpt[String]))
    }.toMap).getOrElse(Map.empty)
  )

  /** 2xx -> Right; 4xx -> Rejected (no retry); connection errors and 5xx retried, then Unreachable. */
  private def send(call: () => Future[WSResponse]): Future[Either[IndexServiceError, WSResponse]] = {
    def attempt(waits: Seq[Int]): Future[Either[IndexServiceError, WSResponse]] = {
      val outcome: Future[Either[IndexServiceError, WSResponse]] = call().map { r =>
        if (r.status >= 200 && r.status < 300) Right(r)
        else if (r.status >= 400 && r.status < 500) Left(rejected(r))
        else Left(IndexServiceUnreachable(s"index service answered ${r.status}"))
      }.recover { case NonFatal(e) => Left(IndexServiceUnreachable(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))) }
      outcome.flatMap {
        case Left(_: IndexServiceUnreachable) if waits.nonEmpty =>
          logger.warn(s"index service unreachable, retrying in ${waits.head} ms")
          after(waits.head).flatMap(_ => attempt(waits.tail))
        case other => Future.successful(other)
      }
    }
    attempt(backoffMs)
  }

  private def rejected(r: WSResponse): IndexServiceRejected = {
    val j = scala.util.Try(Json.parse(r.body)).getOrElse(JsNull)
    IndexServiceRejected(r.status, (j \ "code").asOpt[String].getOrElse("unknown"), (j \ "error").asOpt[String].getOrElse(r.statusText))
  }

  private def after(ms: Int): Future[Unit] = {
    val p = scala.concurrent.Promise[Unit]()
    IndexServiceClient.timer.schedule(new java.util.TimerTask { def run(): Unit = p.success(()) }, ms.toLong)
    p.future
  }
}

object IndexServiceClient {
  private val timer = new java.util.Timer("index-service-client-backoff", true)
}
