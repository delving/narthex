package services

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.Results._
import play.api.mvc.{Action, AnyContent, DefaultActionBuilder, Request}
import play.api.routing.Router
import play.api.routing.sird._
import play.api.test.WsTestClient
import play.core.server.Server

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class IndexServiceClientSpec extends AnyFlatSpec with Matchers {

  implicit val ec: ExecutionContext = ExecutionContext.global
  private def await[T](f: Future[T]): T = Await.result(f, 30.seconds)

  private val req = IndexJobRequest("full", Seq("a/b.xml.zst"), Some("x"), "narthex", "t1", "narthex", force = false)

  private def withServer[T](routes: DefaultActionBuilder => PartialFunction[play.api.mvc.RequestHeader, play.api.mvc.Handler])(
      block: (IndexServiceClient, play.api.libs.ws.WSClient, Int) => T): T = {
    Server.withRouterFromComponents() { c =>
      { case r if routes(c.defaultActionBuilder).isDefinedAt(r) => routes(c.defaultActionBuilder)(r) }
    } { implicit port =>
      WsTestClient.withClient { ws =>
        block(new IndexServiceClient(ws, s"http://localhost:${port.value}", "tok", Seq(10, 10)), ws, port.value)
      }
    }
  }

  "postJob" should "send auth and body, and return the jobId on 202" in {
    var seen: Option[(String, JsValue)] = None
    withServer { Action => {
      case POST(p"/datasets/org/spec/jobs") => Action { (r: Request[AnyContent]) =>
        seen = Some((r.headers.get("Authorization").getOrElse(""), r.body.asJson.get))
        Accepted(Json.obj("jobId" -> "j1"))
      }
    }} { (client, _, _) =>
      await(client.postJob("org", "spec", req)) shouldBe Right("j1")
    }
    val (auth, body) = seen.get
    auth shouldBe "Bearer tok"
    (body \ "kind").as[String] shouldBe "narthex-processed"
    (body \ "mode").as[String] shouldBe "full"
    (body \ "files").as[Seq[String]] shouldBe Seq("a/b.xml.zst")
    (body \ "deletedIds").as[String] shouldBe "x"
    (body \ "doc" \ "type").as[String] shouldBe "narthex"
    (body \ "doc" \ "tags").as[String] shouldBe "t1"
    (body \ "force").as[Boolean] shouldBe false
  }

  it should "map a 403 to IndexServiceRejected with dash's code" in {
    withServer { Action => {
      case POST(p"/datasets/org/spec/jobs") => Action(Forbidden(Json.obj("code" -> "forbidden", "error" -> "wrong org")))
    }} { (client, _, _) =>
      await(client.postJob("org", "spec", req)) shouldBe Left(IndexServiceRejected(403, "forbidden", "wrong org"))
    }
  }

  it should "retry 5xx and then report IndexServiceUnreachable" in {
    val hits = new AtomicInteger(0)
    withServer { Action => {
      case POST(p"/datasets/org/spec/jobs") => Action { hits.incrementAndGet(); InternalServerError("boom") }
    }} { (client, _, _) =>
      await(client.postJob("org", "spec", req)) shouldBe a[Left[_, _]]
      await(client.postJob("org", "spec", req)).left.get shouldBe a[IndexServiceUnreachable]
    }
    hits.get shouldBe 6
  }

  it should "report IndexServiceUnreachable when the connection is refused" in {
    val s = new ServerSocket(0); val port = s.getLocalPort; s.close()
    WsTestClient.withClient { ws =>
      val client = new IndexServiceClient(ws, s"http://localhost:$port", "tok", Seq(10, 10))
      await(client.postJob("org", "spec", req)).left.get shouldBe a[IndexServiceUnreachable]
      await(client.jobStatus("org", "spec", "j1")).left.get shouldBe a[IndexServiceUnreachable]
    }
  }

  it should "make exactly one request on a 4xx" in {
    val hits = new AtomicInteger(0)
    withServer { Action => {
      case POST(p"/datasets/org/spec/jobs") => Action { hits.incrementAndGet(); BadRequest(Json.obj("code" -> "bad_request", "error" -> "x")) }
    }} { (client, _, _) =>
      await(client.postJob("org", "spec", req)) shouldBe Left(IndexServiceRejected(400, "bad_request", "x"))
    }
    hits.get shouldBe 1
  }

  it should "not retry or throw on a 2xx with a non-JSON body" in {
    val hits = new AtomicInteger(0)
    withServer { Action => {
      case POST(p"/datasets/org/spec/jobs") => Action { hits.incrementAndGet(); Accepted("<html>proxy</html>") }
      case GET(p"/datasets/org/spec/jobs/j1") => Action(Ok("<html>proxy</html>"))
    }} { (client, _, _) =>
      await(client.postJob("org", "spec", req)).left.get should matchPattern { case IndexServiceRejected(202, "bad_response", _) => }
      await(client.jobStatus("org", "spec", "j1")).left.get should matchPattern { case IndexServiceRejected(200, "bad_response", _) => }
    }
    hits.get shouldBe 1
  }

  "depublish and publish" should "POST {by} to their route and return seq" in {
    var seen = Vector.empty[(String, JsValue)]
    withServer { Action => {
      case POST(p"/datasets/org/spec/depublish") => Action { (r: Request[AnyContent]) =>
        seen :+= (("depublish", r.body.asJson.get)); Ok(Json.obj("seq" -> 7, "published" -> false)) }
      case POST(p"/datasets/org/spec/publish") => Action { (r: Request[AnyContent]) =>
        seen :+= (("publish", r.body.asJson.get)); Ok(Json.obj("seq" -> 8, "published" -> true)) }
    }} { (client, _, _) =>
      await(client.depublish("org", "spec", "narthex@h")) shouldBe Right(7L)
      await(client.publish("org", "spec", "narthex@h")) shouldBe Right(8L)
    }
    seen.map(x => (x._1, (x._2 \ "by").as[String])) shouldBe Vector(("depublish", "narthex@h"), ("publish", "narthex@h"))
  }

  it should "report a 409 as dataset_locked without retrying" in {
    val hits = new AtomicInteger(0)
    withServer { Action => {
      case POST(p"/datasets/org/spec/depublish") => Action { hits.incrementAndGet(); Conflict(Json.obj("code" -> "dataset_locked", "error" -> "job running")) }
    }} { (client, _, _) =>
      await(client.depublish("org", "spec", "n")) shouldBe Left(IndexServiceRejected(409, "dataset_locked", "job running"))
    }
    hits.get shouldBe 1
  }

  "IndexVisibility" should "retry dataset_locked with backoff, then succeed" in {
    var calls = 0; var slept = Vector.empty[Long]
    val api = new IndexServiceApi {
      def postJob(o: String, s: String, r: IndexJobRequest) = ???
      def jobStatus(o: String, s: String, j: String) = ???
      def publish(o: String, s: String, by: String) = ???
      def depublish(o: String, s: String, by: String) = { calls += 1
        Future.successful(if (calls < 3) Left(IndexServiceRejected(409, "dataset_locked", "busy")) else Right(5L)) }
    }
    IndexVisibility.set(api, "org", "spec", "n", published = false, slept :+= _, Seq(1L, 2L, 3L)) shouldBe Right(5L)
    slept shouldBe Vector(1L, 2L)
  }

  it should "give up when locked past the backoff, and not retry other errors" in {
    var calls = 0
    val api = new IndexServiceApi {
      def postJob(o: String, s: String, r: IndexJobRequest) = ???
      def jobStatus(o: String, s: String, j: String) = ???
      def depublish(o: String, s: String, by: String) = ???
      def publish(o: String, s: String, by: String) = { calls += 1; Future.successful(Left(IndexServiceRejected(403, "forbidden", "no"))) }
    }
    IndexVisibility.set(api, "org", "spec", "n", published = true, _ => (), Seq(1L, 1L)) shouldBe Left(IndexServiceRejected(403, "forbidden", "no"))
    calls shouldBe 1
  }

  "jobStatus" should "map state, seq, counts and targets" in {
    val json = Json.parse(
      """{"jobId":"j1","state":"indexing","seq":7,"error":null,
        |"ingest":{"counts":{"received":10,"refused":2},"ingestSeconds":0.5},
        |"index":{"hub3":{"cursor":6,"state":"ok","indexed":6,"deleted":0,"refused":0},"alt":{"cursor":3,"state":"failed","indexed":3,"deleted":0,"refused":1}}}""".stripMargin)
    withServer { Action => {
      case GET(p"/datasets/org/spec/jobs/j1") => Action(Ok(json))
    }} { (client, _, _) =>
      val st = await(client.jobStatus("org", "spec", "j1")).right.get
      st.jobId shouldBe "j1"
      st.state shouldBe "indexing"
      st.seq shouldBe Some(7L)
      st.error shouldBe None
      st.counts shouldBe Map("received" -> 10L, "refused" -> 2L)
      st.targets shouldBe Map(
        "hub3" -> IndexTargetState(6L, "ok", None),
        "alt" -> IndexTargetState(3L, "failed", None))
    }
  }

  it should "tolerate a status without ingest or index" in {
    withServer { Action => {
      case GET(p"/datasets/org/spec/jobs/j2") => Action(Ok(Json.obj("jobId" -> "j2", "state" -> "queued")))
    }} { (client, _, _) =>
      val st = await(client.jobStatus("org", "spec", "j2")).right.get
      st shouldBe IndexJobStatus("j2", "queued", None, None, Map.empty, Map.empty)
    }
  }

  it should "map a 404 to IndexServiceRejected" in {
    withServer { Action => {
      case GET(p"/datasets/org/spec/jobs/nope") => Action(NotFound(Json.obj("code" -> "not_found", "error" -> "no job")))
    }} { (client, _, _) =>
      await(client.jobStatus("org", "spec", "nope")) shouldBe Left(IndexServiceRejected(404, "not_found", "no job"))
    }
  }
}
