//===========================================================================
//    Copyright 2014 Delving B.V.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//===========================================================================

package harvest

import dataset.DsInfo
import dataset.DsInfo.DsState._
import java.io.{BufferedReader, BufferedWriter, File, FileOutputStream, InputStreamReader, OutputStreamWriter}
import java.util.zip.{ZipEntry, ZipOutputStream}

import akka.actor.{Actor, ActorLogging, Props}
import akka.pattern.pipe
import dataset.DatasetActor._
import dataset.DatasetContext
import harvest.Harvester.{HarvestAdLib, HarvestComplete, HarvestPMH, HarvestDownloadLink}
import harvest.Harvesting.{AdLibHarvestPage, HarvestError, NoRecordsMatch, PMHHarvestPage}
import nxutil.Utils.actorWork
import org.apache.commons.io.FileUtils
import play.api.Logger
import play.api.libs.ws.WSClient
import services.ProgressReporter.ProgressState._
import services.{ProgressReporter, StringHandling}

import scala.concurrent._
import scala.util.{Failure, Success}
import scala.util.control.NonFatal
import scala.language.postfixOps

object Harvester {

  case class HarvestDownloadLink(strategy: HarvestStrategy, downloadLink: String, dsInfo: DsInfo)

  case class HarvestAdLib(strategy: HarvestStrategy, url: String, database: String, search: String)

  case class HarvestPMH(strategy: HarvestStrategy, url: String, set: String, prefix: String, recordId: String)

  case class HarvestComplete(strategy: HarvestStrategy, fileOpt: Option[File], noRecordsMatch: Boolean = false)

  case class AdLibSingleRecordHarvest(
    recordOffset: Int,
    url: String,
    database: String,
    search: String,
    strategy: HarvestStrategy,
    originalPageUrl: String
  )

  def props(datasetContext: DatasetContext, timeOut: Long, wsApi: WSClient,
            harvestingExecutionContext: ExecutionContext) = Props(classOf[Harvester], timeOut, datasetContext,
    wsApi, harvestingExecutionContext)
}

class Harvester(timeout: Long, datasetContext: DatasetContext, wsApi: WSClient,
                implicit val harvestingExecutionContext: ExecutionContext) extends Actor
  with Harvesting
  with ActorLogging {

  private val logger = Logger(getClass)

  var tempFileOpt: Option[File] = None
  var zipOutputOpt: Option[ZipOutputStream] = None
  var pageCount = 0
  var progressOpt: Option[ProgressReporter] = None

  // Error recovery state
  var harvestErrors: Map[String, String] = Map.empty
  var errorRecords: List[(String, String, String)] = List.empty // (recordId, xml, error)
  var errorPagesSubmitted: Int = 0
  var errorPagesProcessed: Int = 0
  var continueOnError: Boolean = false
  var errorThresholdOpt: Option[Int] = None
  var recordsProcessed: Int = 0

  private def cleanup(): Unit = {
    zipOutputOpt.foreach { zipOutput =>
      try {
        zipOutput.close()
      } catch {
        case NonFatal(e) =>
          log.warning(s"Error closing ZipOutputStream: ${e.getMessage}")
      }
    }
    zipOutputOpt = None
    
    tempFileOpt.foreach { tempFile =>
      if (tempFile.exists() && !tempFile.delete()) {
        log.warning(s"Failed to delete temp file: ${tempFile.getAbsolutePath}")
      }
    }
    tempFileOpt = None
  }

  override def postStop(): Unit = {
    cleanup()

    // Also ensure semaphores are released when Harvester stops
    // (Though this should be handled by parent DatasetActor, being extra safe)
    log.debug(s"Harvester postStop for dataset: ${datasetContext.dsInfo.spec}")

    super.postStop()
  }

  /**
   * Breaks a failed page URL into individual record URLs for retry.
   * Mimics Go implementation: harvestError.createSingleRecordPages()
   */
  def breakPageIntoRecords(pageUrl: String, limit: Int, offset: Int): List[String] = {
    logger.info(s"Breaking failed page into $limit single-record requests: $pageUrl")

    (0 until limit).map { i =>
      val recordOffset = offset + i
      // Parse and rebuild URL with limit=1 and specific startFrom
      val url = new java.net.URL(pageUrl)
      val query = url.getQuery
      val params = query.split("&").map { param =>
        val parts = param.split("=", 2)
        if (parts.length == 2) (parts(0), parts(1)) else (parts(0), "")
      }.toMap

      val newParams = params + ("limit" -> "1", "startFrom" -> recordOffset.toString)
      val newQuery = newParams.map { case (k, v) => s"$k=$v" }.mkString("&")

      s"${url.getProtocol}://${url.getHost}${if (url.getPort != -1) s":${url.getPort}" else ""}${url.getPath}?$newQuery"
    }.toList
  }

  /**
   * Checks if error rate has exceeded configured threshold.
   * Returns true if harvesting should continue, false if should stop.
   */
  def checkErrorThreshold(): Boolean = {
    errorThresholdOpt match {
      case None => true // No threshold configured, always continue
      case Some(threshold) if threshold == 0 => true // 0 means unlimited errors
      case Some(threshold) =>
        val totalRecords = recordsProcessed + errorRecords.size
        if (totalRecords == 0) return true

        val errorPercentage = (errorRecords.size.toDouble / totalRecords.toDouble) * 100
        val shouldContinue = errorPercentage < threshold

        if (!shouldContinue) {
          logger.error(
            s"Error threshold exceeded: ${errorPercentage.formatted("%.2f")}% errors " +
            s"(${errorRecords.size} / $totalRecords) exceeds limit of $threshold%"
          )
        }

        shouldContinue
    }
  }

  /**
   * Creates error output files: .errors.zip and .errors.txt
   * Mimics Go implementation's error file creation
   */
  def createErrorFiles(): (Option[File], Option[File]) = {
    if (errorRecords.isEmpty && harvestErrors.isEmpty) {
      return (None, None)
    }

    logger.info(s"Creating error files: ${errorRecords.size} failed records, ${harvestErrors.size} page errors")

    // Create .errors.zip with failed record XML
    val errorsZipOpt = if (errorRecords.nonEmpty) {
      try {
        val errorsZipFile = File.createTempFile("narthex-harvest-errors", ".zip")
        val fos = new FileOutputStream(errorsZipFile)
        val zipOut = new ZipOutputStream(fos)

        errorRecords.zipWithIndex.foreach { case ((recordId, xml, error), idx) =>
          zipOut.putNextEntry(new ZipEntry(s"error_record_${recordId}_$idx.xml"))
          zipOut.write(xml.getBytes("UTF-8"))
          zipOut.closeEntry()
        }

        zipOut.close()
        fos.close()
        Some(errorsZipFile)
      } catch {
        case NonFatal(e) =>
          logger.error(s"Failed to create errors ZIP file: ${e.getMessage}", e)
          None
      }
    } else {
      None
    }

    // Create .errors.txt with error log
    val errorLogOpt = if (harvestErrors.nonEmpty || errorRecords.nonEmpty) {
      try {
        val errorLogFile = File.createTempFile("narthex-harvest-errors", ".txt")
        val writer = new BufferedWriter(new OutputStreamWriter(
          new FileOutputStream(errorLogFile), "UTF-8"
        ))

        writer.write(s"AdLib Harvest Error Report\n")
        writer.write(s"===========================\n\n")
        writer.write(s"Total Pages with Errors: ${harvestErrors.size}\n")
        writer.write(s"Total Failed Records: ${errorRecords.size}\n")
        writer.write(s"Error Recovery Attempts: $errorPagesSubmitted\n")
        writer.write(s"Error Recoveries Processed: $errorPagesProcessed\n\n")

        if (harvestErrors.nonEmpty) {
          writer.write(s"Page Errors:\n")
          writer.write(s"------------\n")
          harvestErrors.foreach { case (pageUrl, errorMsg) =>
            writer.write(s"URL: $pageUrl\n")
            writer.write(s"Error: $errorMsg\n\n")
          }
        }

        if (errorRecords.nonEmpty) {
          writer.write(s"\nFailed Records:\n")
          writer.write(s"---------------\n")
          errorRecords.foreach { case (recordId, _, errorMsg) =>
            writer.write(s"Record ID: $recordId\n")
            writer.write(s"Error: $errorMsg\n\n")
          }
        }

        writer.close()
        Some(errorLogFile)
      } catch {
        case NonFatal(e) =>
          logger.error(s"Failed to create error log file: ${e.getMessage}", e)
          None
      }
    } else {
      None
    }

    (errorsZipOpt, errorLogOpt)
  }

  def addPage(page: String): Int = {
    try {
      // lazily create the temp zip file output
      val zipOutput = zipOutputOpt.getOrElse {
        val newTempFile = File.createTempFile("narthex-harvest", ".zip")
        tempFileOpt = Some(newTempFile)
        val fos = new FileOutputStream(newTempFile)
        try {
          val newZipOutput = new ZipOutputStream(fos)
          zipOutputOpt = Some(newZipOutput)
          newZipOutput
        } catch {
          case NonFatal(e) =>
            fos.close()
            throw e
        }
      }
      val harvestPageName = s"harvest_$pageCount.xml"
      zipOutput.putNextEntry(new ZipEntry(harvestPageName))
      zipOutput.write(page.getBytes("UTF-8"))
      zipOutput.closeEntry()
      pageCount += 1
      pageCount
    } catch {
      case NonFatal(e) =>
        log.error(s"Error adding page to harvest zip: ${e.getMessage}", e)
        cleanup()
        throw e
    }
  }

  def finish(strategy: HarvestStrategy, errorOpt: Option[String]) = {
    // Close ZIP output stream but keep tempFile for potential use
    zipOutputOpt.foreach { zipOutput =>
      try {
        zipOutput.close()
      } catch {
        case NonFatal(e) =>
          log.warning(s"Error closing ZipOutputStream in finish: ${e.getMessage}")
      }
    }
    zipOutputOpt = None

    // Create error files if any errors occurred
    val (errorsZipOpt, errorLogOpt) = createErrorFiles()

    // Update dataset properties with error metrics
    if (errorRecords.nonEmpty || errorPagesSubmitted > 0) {
      datasetContext.dsInfo.setSingularLiteralProps(
        harvestErrorCount -> errorRecords.size,
        harvestErrorRecoveryAttempts -> errorPagesSubmitted
      )
    }

    log.info(
      s"Finished $strategy harvest for dataset $datasetContext: " +
      s"error=$errorOpt, errors=${errorRecords.size}, recovery_attempts=$errorPagesSubmitted, " +
      s"records_processed=$recordsProcessed"
    )

    errorOpt match {
      case Some("noRecordsMatch") =>
        log.info(s"Finished incremental harvest with noRecordsMatch")
        context.parent ! HarvestComplete(strategy, None, true)
      case None | Some("noRecordsMatchRecoverable") =>
        strategy match {
          case Sample =>
            context.parent ! HarvestComplete(strategy, None)
          case _ =>
            datasetContext.sourceRepoOpt match {
              case Some(sourceRepo) =>
                Future {
                  val acceptZipReporter = ProgressReporter(COLLECTING, context.parent)
                  val fileOption = sourceRepo.acceptFile(tempFileOpt.get, acceptZipReporter)

                  // Save error files if they exist
                  errorsZipOpt.foreach { errZip =>
                    val errorZipDest = new File(datasetContext.datasetDir, "harvest_errors.zip")
                    FileUtils.copyFile(errZip, errorZipDest)
                    log.info(s"Saved error ZIP: ${errorZipDest.getAbsolutePath}")
                  }
                  errorLogOpt.foreach { errLog =>
                    val errorLogDest = new File(datasetContext.datasetDir, "harvest_errors.txt")
                    FileUtils.copyFile(errLog, errorLogDest)
                    log.info(s"Saved error log: ${errorLogDest.getAbsolutePath}")
                  }

                  log.info(s"Zip file accepted: $fileOption")
                  context.parent ! HarvestComplete(strategy, fileOption)
                } onComplete {
                  case Success(v) => v
                  case Failure(e) =>
                    log.info(s"error on accepting sip-file: ${e}")
                    context.parent ! WorkFailure(e.getMessage)
                }
              case None =>
                context.parent ! WorkFailure(s"No source repo for $datasetContext")
            }
        }
        case Some(message) =>
          context.parent ! WorkFailure(message)
    }
  }

  def handleFailure(future: Future[Any], strategy: HarvestStrategy, message: String) = {
    future.onComplete {
      case Success(_) => ()
      case Failure(e) =>
        log.info(s"Harvest failure", e)
        finish(strategy, Some(e.toString))
    }
  }

  def receive = {

    // http://umu.adlibhosting.com/api/wwwopac.ashx?xmltype=grouped&limit=50&database=collect&search=modification%20greater%20%272014-12-01%27

    case HarvestAdLib(strategy, url, database, search) => actorWork(context) {
      log.info(s"Harvesting $url $database to $datasetContext")

      // Initialize error recovery settings from dataset config
      continueOnError = datasetContext.dsInfo.getOptionalProperty(harvestContinueOnError).getOrElse(false)
      errorThresholdOpt = datasetContext.dsInfo.getOptionalProperty(harvestErrorThreshold).map(_.toInt)
      log.info(s"Error recovery enabled: $continueOnError, threshold: $errorThresholdOpt")

      val futurePage = fetchAdLibPage(timeout, wsApi, strategy, url, database, search)
      handleFailure(futurePage, strategy, "adlib harvest")
      strategy match {
        case Sample =>
          val slug = StringHandling.slugify(url)
          val rawXml = datasetContext.createRawFile(s"$slug.xml")
          futurePage.map {
            case page: AdLibHarvestPage =>
              FileUtils.writeStringToFile(rawXml, page.records, "UTF-8")
              finish(strategy, None)
          }
        case _ =>
          progressOpt = Some(ProgressReporter(HARVESTING, context.parent))
          // todo: if None comes back there's something wrong
          futurePage pipeTo self
      }
    }

    case AdLibHarvestPage(records, url, database, search, strategy, diagnostic) => actorWork(context) {
      Try(addPage(records)) match {
        case Success(pageNumber) =>
          recordsProcessed += diagnostic.pageItems

          strategy match {
            case ModifiedAfter(_, _) =>
              progressOpt.get.sendPage(pageNumber) // This compensates for AdLib's failure to report number of hits
            case _ =>
              progressOpt.get.sendPercent(diagnostic.percentComplete)
          }
          log.info(s"Harvest Page: $pageNumber - $url $database to $datasetContext: $diagnostic")

          if (diagnostic.totalItems == 0) {
            finish(strategy, Some("noRecordsMatch"))
          }
          else if (diagnostic.isLast) {
            finish(strategy, None)
          }
          else {
            val futurePage = fetchAdLibPage(timeout, wsApi, strategy, url, database, search, Some(diagnostic))
            handleFailure(futurePage, strategy, "adlib harvest page")
            futurePage pipeTo self
          }

        case Failure(e) if continueOnError =>
          // Error recovery path: break page into individual records
          log.warn(s"Failed to process harvest page, breaking into individual records: ${e.getMessage}")
          harvestErrors += (url -> e.toString)

          // Build the full page URL with current parameters for reconstruction
          val fullPageUrl = s"$url?database=$database&search=$search&xmltype=grouped&limit=${diagnostic.pageItems}&startFrom=${diagnostic.current}"
          val recordUrls = breakPageIntoRecords(fullPageUrl, diagnostic.pageItems, diagnostic.current)

          recordUrls.zipWithIndex.foreach { case (_, idx) =>
            self ! AdLibSingleRecordHarvest(
              recordOffset = diagnostic.current + idx,
              url = url,
              database = database,
              search = search,
              strategy = strategy,
              originalPageUrl = fullPageUrl
            )
          }

          // Continue with next page if not last
          if (!diagnostic.isLast) {
            val futurePage = fetchAdLibPage(timeout, wsApi, strategy, url, database, search, Some(diagnostic))
            handleFailure(futurePage, strategy, "adlib harvest page")
            futurePage pipeTo self
          }

        case Failure(e) =>
          // Error recovery disabled, fail immediately
          log.error(s"Failed to process harvest page: ${e.getMessage}", e)
          finish(strategy, Some(e.toString))
      }
    }

    case AdLibSingleRecordHarvest(recordOffset, url, database, search, strategy, originalPageUrl) => actorWork(context) {
      errorPagesSubmitted += 1
      log.debug(s"Attempting single-record harvest at offset $recordOffset")

      val futurePage = fetchAdLibPage(
        timeout, wsApi, strategy, url, database, search,
        diagnosticOption = None,
        limit = 1,
        startFrom = Some(recordOffset)
      )

      futurePage.onComplete {
        case Success(AdLibHarvestPage(records, _, _, _, _, _)) =>
          Try(addPage(records)) match {
            case Success(_) =>
              log.debug(s"Successfully recovered record at offset $recordOffset")
              recordsProcessed += 1
            case Failure(e) =>
              log.warn(s"Failed to recover record at offset $recordOffset: ${e.getMessage}")
              errorRecords :+= (recordOffset.toString, records, e.toString)
          }
          errorPagesProcessed += 1

        case Success(HarvestError(error, _)) =>
          log.warn(s"Error retrieving single record at offset $recordOffset: $error")
          errorRecords :+= (recordOffset.toString, "", error)
          errorPagesProcessed += 1

        case Failure(e) =>
          log.warn(s"Failed single-record harvest at offset $recordOffset: ${e.getMessage}")
          errorRecords :+= (recordOffset.toString, "", e.toString)
          errorPagesProcessed += 1

        case _ =>
          log.warn(s"Unexpected response for single-record harvest at offset $recordOffset")
          errorPagesProcessed += 1
      }

      // Check error threshold
      if (!checkErrorThreshold()) {
        finish(strategy, Some("Error threshold exceeded"))
      }
    }

    case HarvestPMH(strategy: HarvestStrategy, raw_url, set, prefix, recordId) => actorWork(context) {
      val url = s"${raw_url.stripSuffix("?")}?"
      log.info(s"Harvesting $strategy: $url $set $prefix to $datasetContext")
      val harvestRecord = if (!recordId.isEmpty) Option(recordId) else None
      val futurePage = fetchPMHPage(1, timeout, wsApi, strategy, url, set, prefix, None, harvestRecord)
      handleFailure(futurePage, strategy, "pmh harvest")
      strategy match {
        case Sample =>
          val slug = StringHandling.slugify(url)
          val rawXml = datasetContext.createRawFile(s"$slug.xml")
          futurePage.map {
            case page: PMHHarvestPage =>
              FileUtils.writeStringToFile(rawXml, page.records, "UTF-8")
              finish(strategy, None)
          }
        case _ =>
          progressOpt = Some(ProgressReporter(HARVESTING, context.parent))
          // todo: if None comes back there's something wrong
          futurePage pipeTo self
      }
    }

    case HarvestDownloadLink(strategy: HarvestStrategy, downloadLink: String, dsInfo: DsInfo) => actorWork(context) {

      // create ProgresReporter and loop to sendValue while downloading. This should provide  feedback to the interface.
      // There might be an issue how and when it updates

      def writeFile(reader: java.io.InputStream, writer: java.io.OutputStream): Unit = {
        val readBuffer = Array.fill[Byte](2048)(0)
        while (true) {
          val bytesRead = reader.read(readBuffer)
          if (bytesRead == -1) {
            return
          }
          writer.write(readBuffer, 0, bytesRead)
          writer.flush()
        }
      }

      log.info(s"Harvesting download link $downloadLink")
      val parts = downloadLink.split('/')
      val tail = parts(parts.length - 1)
      val tailSplit = tail.split('?')
      val filename = tailSplit(0)
      val file = datasetContext.createRawFile(filename)

      val url = new java.net.URL(downloadLink)
      var reader: java.io.InputStream = null
      var writer: java.io.OutputStream = null
      try {
        reader = url.openStream()
        writer = new FileOutputStream(file)
        writeFile(reader, writer)
        log.info(s"Written download to ${file.toString()}")

      } catch {
        case e: Throwable => {
          e.printStackTrace()
          finish(strategy, Some(e.toString()))
        }
      } finally {
        if (reader != null) {
          reader.close()
        }
        if (writer != null) {
          writer.close()
        }
      }

      // TODO: finish the progress bar
      strategy match {
        case Sample =>
          finish(strategy, None)
        case _ =>
          log.info(s"finished download with strategy: ${strategy}")
          tempFileOpt = Some(file)
          finish(strategy, None)
      }
    }

    case PMHHarvestPage(records, url, set, prefix, total, strategy, resumptionToken) => actorWork(context) {
      val pageNumber = addPage(records)
      log.info(s"Harvest Page $pageNumber to $datasetContext: $resumptionToken")
      resumptionToken.map { token =>
        if (token.hasPercentComplete) {
          progressOpt.get.sendPercent(token.percentComplete)
        }
        else {
          progressOpt.get.sendPage(pageCount)
        }
        val futurePage = fetchPMHPage(pageNumber, timeout, wsApi, strategy, url, set, prefix, resumptionToken)
        handleFailure(futurePage, strategy, "pmh harvest page")
        futurePage pipeTo self
      } getOrElse {
        finish(strategy, None)
      }
    }

    case HarvestError(error, strategy) =>
      finish(strategy, Some(error))

    case NoRecordsMatch(message, strategy) =>
      logger.debug("noRecordsMatch (pre-finish)")
      finish(strategy, Some(message))
  }

}
