package discovery

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import play.api.Logger
import play.api.libs.ws.WSClient

/**
 * Verifies record counts for OAI-PMH sets by calling ListIdentifiers
 * and reading completeListSize from the resumptionToken.
 *
 * Runs sequentially with throttling to avoid overwhelming the endpoint.
 */
@Singleton
class SetCountVerifier @Inject()(
  wsClient: WSClient,
  actorSystem: akka.actor.ActorSystem
)(implicit ec: ExecutionContext) {

  private val logger = Logger(getClass)
  private val REQUEST_TIMEOUT = 30.seconds
  private val DEFAULT_DELAY_MS = 500

  // Track running verifications by sourceId
  @volatile private var runningJobs: Map[String, VerificationProgress] = Map.empty

  case class VerificationProgress(
    total: Int,
    checked: Int,
    withRecords: Int,
    errors: Int,
    status: String // "running", "complete", "failed", "idle"
  )

  def getProgress(sourceId: String): VerificationProgress = {
    runningJobs.getOrElse(sourceId, VerificationProgress(0, 0, 0, 0, "idle"))
  }

  def isRunning(sourceId: String): Boolean = {
    runningJobs.get(sourceId).exists(_.status == "running")
  }

  /**
   * Verify record counts for a list of setSpecs.
   * Fire-and-forget — returns a Future that completes when all sets are checked.
   */
  def verify(
    sourceId: String,
    baseUrl: String,
    prefix: String,
    setSpecs: List[String],
    delayMs: Int = DEFAULT_DELAY_MS,
    onProgress: (Map[String, Int], Map[String, String]) => Unit = (_, _) => ()
  ): Future[(Map[String, Int], Map[String, String])] = {

    if (isRunning(sourceId)) {
      return Future.failed(new IllegalStateException(s"Verification already running for $sourceId"))
    }

    val total = setSpecs.size
    runningJobs = runningJobs + (sourceId -> VerificationProgress(total, 0, 0, 0, "running"))

    logger.info(s"Starting record count verification for $sourceId: $total sets to check")

    // Process sets sequentially with delay
    val resultFuture = setSpecs.zipWithIndex.foldLeft(
      Future.successful((Map.empty[String, Int], Map.empty[String, String]))
    ) { case (accFuture, (setSpec, index)) =>
      accFuture.flatMap { case (counts, errors) =>
        // Delay between requests (not before first)
        val delayFuture = if (index > 0) {
          akka.pattern.after(delayMs.milliseconds, actorSystem.scheduler)(Future.successful(()))
        } else {
          Future.successful(())
        }

        delayFuture.flatMap { _ =>
          fetchRecordCount(baseUrl, prefix, setSpec).map { result =>
            val (newCounts, newErrors) = result match {
              case Right(count) => (counts + (setSpec -> count), errors)
              case Left(error) => (counts, errors + (setSpec -> error))
            }

            val checked = index + 1
            val withRecords = newCounts.values.count(_ > 0)
            val errorCount = newErrors.size

            runningJobs = runningJobs + (sourceId ->
              VerificationProgress(total, checked, withRecords, errorCount, "running"))

            // Progress callback every 50 sets
            if (checked % 50 == 0 || checked == total) {
              logger.info(s"Verification progress for $sourceId: $checked/$total ($withRecords with records, $errorCount errors)")
              onProgress(newCounts, newErrors)
            }

            (newCounts, newErrors)
          }
        }
      }
    }

    resultFuture.map { result =>
      val (counts, errors) = result
      val withRecords = counts.values.count(_ > 0)
      runningJobs = runningJobs + (sourceId ->
        VerificationProgress(total, total, withRecords, errors.size, "complete"))
      logger.info(s"Verification complete for $sourceId: $withRecords with records, ${counts.values.count(_ == 0)} empty, ${errors.size} errors")
      result
    }.recover { case e: Exception =>
      logger.error(s"Verification failed for $sourceId: ${e.getMessage}", e)
      runningJobs = runningJobs + (sourceId ->
        VerificationProgress(total, 0, 0, 0, "failed"))
      (Map.empty[String, Int], Map.empty[String, String])
    }
  }

  /**
   * Fetch record count for a single set via ListIdentifiers.
   */
  private def fetchRecordCount(baseUrl: String, prefix: String, setSpec: String): Future[Either[String, Int]] = {
    val separator = if (baseUrl.contains("?")) "&" else "?"
    val url = s"${baseUrl.stripSuffix("/")}${separator}verb=ListIdentifiers&metadataPrefix=$prefix&set=$setSpec"

    wsClient.url(url)
      .withRequestTimeout(REQUEST_TIMEOUT)
      .get()
      .map { response =>
        if (response.status == 200) {
          OaiListSetsParser.parseCompleteListSize(response.body)
        } else {
          Left(s"HTTP ${response.status}: ${response.statusText}")
        }
      }
      .recover { case e: Exception =>
        Left(s"Request failed: ${e.getMessage}")
      }
  }
}
