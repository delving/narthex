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

import java.io.{File, FileOutputStream}
import java.util.zip.{ZipEntry, ZipOutputStream}

import akka.actor.{Actor, ActorLogging, Props}
import akka.pattern.pipe
import dataset.DatasetActor._
import dataset.DatasetContext
import harvest.Harvester.{HarvestAdLib, HarvestComplete, HarvestPMH}
import harvest.Harvesting.{AdLibHarvestPage, HarvestError, NoRecordsMatch, PMHHarvestPage}
import nxutil.Utils.actorWork
import org.apache.commons.io.FileUtils
import play.api.Logger
import play.api.libs.ws.WSAPI
import services.ProgressReporter.ProgressState._
import services.{ProgressReporter, StringHandling}

import scala.concurrent._
import scala.language.postfixOps

object Harvester {

  case class HarvestAdLib(strategy: HarvestStrategy, url: String, database: String, search: String)

  case class HarvestPMH(strategy: HarvestStrategy, url: String, set: String, prefix: String)

  case class HarvestComplete(strategy: HarvestStrategy, fileOpt: Option[File], noRecordsMatch: Boolean = false)

  def props(datasetContext: DatasetContext, timeOut: Long, wsApi: WSAPI,
            harvestingExecutionContext: ExecutionContext) = Props(classOf[Harvester], timeOut, datasetContext,
    wsApi, harvestingExecutionContext)
}

class Harvester(timeout: Long, datasetContext: DatasetContext, wsApi: WSAPI,
                implicit val harvestingExecutionContext: ExecutionContext) extends Actor
  with Harvesting
  with ActorLogging {

  var tempFileOpt: Option[File] = None
  var zipOutputOpt: Option[ZipOutputStream] = None
  var pageCount = 0
  var progressOpt: Option[ProgressReporter] = None

  def addPage(page: String): Int = {
    // lazily create the temp zip file output
    val zipOutput = zipOutputOpt.getOrElse {
      val newTempFile = File.createTempFile("narthex-harvest", ".zip")
      tempFileOpt = Some(newTempFile)
      val newZipOutput = new ZipOutputStream(new FileOutputStream(newTempFile))
      zipOutputOpt = Some(newZipOutput)
      newZipOutput
    }
    val harvestPageName = s"harvest_$pageCount.xml"
    zipOutput.putNextEntry(new ZipEntry(harvestPageName))
    zipOutput.write(page.getBytes("UTF-8"))
    zipOutput.closeEntry()
    pageCount += 1
    pageCount
  }

  def finish(strategy: HarvestStrategy, errorOpt: Option[String]) = {
    zipOutputOpt.foreach(_.close())
    log.info(s"Finished $strategy harvest error=$errorOpt")
    errorOpt match {
      case Some("noRecordsMatch") =>
        log.info(s"Finished incremental harvest with noRecordsMatch")
        context.parent ! HarvestComplete(strategy, None, true)
      case Some(message) =>
        context.parent ! WorkFailure(message)
      case None =>
        strategy match {
          case Sample =>
            context.parent ! HarvestComplete(strategy, None)
          case _ =>
            datasetContext.sourceRepoOpt match {
              case Some(sourceRepo) =>
                Future {
                  val acceptZipReporter = ProgressReporter(COLLECTING, context.parent)
                  val fileOption = sourceRepo.acceptFile(tempFileOpt.get, acceptZipReporter)
                  log.info(s"Zip file accepted: $fileOption")
                  context.parent ! HarvestComplete(strategy, fileOption)
                } onFailure {
                  case e: Exception =>
                    log.info(s"error on accepting sip-file: ${e}")
                    context.parent ! WorkFailure(e.getMessage)
                }
              case None =>
                context.parent ! WorkFailure(s"No source repo for $datasetContext")
            }
        }
    }
  }

  def handleFailure(future: Future[Any], strategy: HarvestStrategy, message: String) = {
    future.onFailure {
      case e: Exception =>
        log.info(s"Harvest failure", e)
        finish(strategy, Some(e.toString))
    }
  }

  def receive = {

    // http://umu.adlibhosting.com/api/wwwopac.ashx?xmltype=grouped&limit=50&database=collect&search=modification%20greater%20%272014-12-01%27

    case HarvestAdLib(strategy, url, database, search) => actorWork(context) {
      log.info(s"Harvesting $url $database to $datasetContext")
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
      val pageNumber = addPage(records)
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
    }

    case HarvestPMH(strategy: HarvestStrategy, raw_url, set, prefix) => actorWork(context) {
      val url = s"${raw_url.stripSuffix("?")}?"
      log.info(s"Harvesting $strategy: $url $set $prefix to $datasetContext")
      val futurePage = fetchPMHPage(timeout, wsApi, strategy, url, set, prefix)
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
        val futurePage = fetchPMHPage(timeout, wsApi, strategy, url, set, prefix, resumptionToken)
        handleFailure(futurePage, strategy, "pmh harvest page")
        futurePage pipeTo self
      } getOrElse {
        finish(strategy, None)
      }
    }

    case HarvestError(error, strategy) =>
      finish(strategy, Some(error))

    case NoRecordsMatch(message, strategy) =>
      Logger.debug("noRecordsMatch (pre-finish)")
      finish(strategy, Some(message))
  }
}
