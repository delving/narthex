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

package actors

import java.io.{File, FileOutputStream}
import java.util.zip.{ZipEntry, ZipOutputStream}

import actors.Harvester._
import actors.OrgSupervisor.ActorShutdown
import akka.actor.{Actor, Props}
import akka.pattern.pipe
import org.apache.commons.io.FileUtils.deleteQuietly
import org.joda.time.DateTime
import play.api.Logger
import services.DatasetState._
import services.Harvesting._
import services._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.postfixOps

object Harvester {

  case class HarvestAdLib(url: String, database: String, modifiedAfter: Option[DateTime])

  case class HarvestPMH(url: String, set: String, prefix: String, modifiedAfter: Option[DateTime])

  case class HarvestProgress(percent: Int)

  case class HarvestComplete(error: Option[String])

  case class CollectSource()

  def props(datasetRepo: DatasetRepo, harvestRepo: HarvestRepo) = Props(new Harvester(datasetRepo, harvestRepo))

  global.getClass // to avoid optimizing the import away
}

class Harvester(val datasetRepo: DatasetRepo, harvestRepo: HarvestRepo) extends Actor with RecordHandling with Harvesting {
  val log = Logger
  var tempFile = File.createTempFile("narthex-harvest", ".zip")
  val zip = new ZipOutputStream(new FileOutputStream(tempFile))
  var pageCount = 0
  var harvestProgress: Option[ProgressReporter] = None

  def addPage(page: String): Int = {
    val fileName = s"harvest_$pageCount.xml"
    zip.putNextEntry(new ZipEntry(fileName))
    zip.write(page.getBytes("UTF-8"))
    zip.closeEntry()
    pageCount += 1
    pageCount
  }

  def handleFailure(future: Future[Any], message: String) = {
    future.onFailure {
      case e: Exception =>
        log.warn(s"Harvest failure", e)
        self ! HarvestComplete(Some(e.toString))
    }
  }

  def receive = {

    case ActorShutdown(name) =>
      log.info(s"Interrupt harvesting $datasetRepo")
      harvestProgress.foreach(_.bomb = Some(sender))

    case HarvestAdLib(url, database, modifiedAfter) =>
      log.info(s"Harvesting $url $database to $datasetRepo")
      val futurePage = fetchAdLibPage(url, database, modifiedAfter)
      handleFailure(futurePage, "adlib harvest")
      harvestProgress = Some(ProgressReporter(HARVESTING, datasetRepo.datasetDb))
      futurePage pipeTo self

    case AdLibHarvestPage(records, error: Option[String], url, database, modifiedAfter, diagnostic) =>
      if (error.isDefined) {
        self ! HarvestComplete(error)
      }
      else harvestProgress.foreach { progressReporter =>
        val pageNumber = addPage(records)
        if (progressReporter.sendPercent(diagnostic.percentComplete)) {
          log.info(s"Harvest Page: $pageNumber - $url $database to $datasetRepo: $diagnostic")
          if (diagnostic.isLast) {
            self ! HarvestComplete(None)
          }
          else {
            val futurePage = fetchAdLibPage(url, database, modifiedAfter, Some(diagnostic))
            handleFailure(futurePage, "adlib harvest page")
            futurePage pipeTo self
          }
        }
        else {
          self ! HarvestComplete(Some(s"Interrupted while processing $datasetRepo"))
        }
      }

    case HarvestPMH(url, set, prefix, modifiedAfter) =>
      log.info(s"Harvesting $url $set $prefix to $datasetRepo")
      harvestProgress = Some(ProgressReporter(HARVESTING, datasetRepo.datasetDb))
      val futurePage = fetchPMHPage(url, set, prefix, modifiedAfter)
      handleFailure(futurePage, "pmh harvest")
      futurePage pipeTo self

    case PMHHarvestPage(records, url, set, prefix, total, error, resumptionToken) =>
      if (error.isDefined) {
        self ! HarvestComplete(error)
      }
      else {
        val pageNumber = addPage(records)
        log.info(s"Harvest Page $pageNumber to $datasetRepo: $resumptionToken")
        harvestProgress.foreach { progressReporter =>
          resumptionToken match {
            case None =>
              self ! HarvestComplete(None)
            case Some(token) =>
              val keepHarvesting = if (token.hasPercentComplete) {
                progressReporter.sendPercent(token.percentComplete)
              }
              else {
                progressReporter.sendPage(pageCount)
              }
              if (keepHarvesting) {
                val futurePage = fetchPMHPage(url, set, prefix, None, Some(token))
                handleFailure(futurePage, "pmh harvest page")
                futurePage pipeTo self
              }
              else {
                self ! HarvestComplete(Some("Interrupted while harvesting"))
              }
          }
        }
      }

    case HarvestComplete(error) =>
      log.info(s"Harvest complete $datasetRepo error: $error")
      zip.close()
      error match {
        case Some(errorString) =>
          deleteQuietly(tempFile)
          datasetRepo.datasetDb.setStatus(EMPTY, error = errorString)
          context.stop(self)
        case None =>
          val progressReporter = ProgressReporter(COLLECTING, datasetRepo.datasetDb)
          val file = harvestRepo.acceptZipFile(tempFile, progressReporter)
          // todo: file should be parsed and cause basex updates
          self ! CollectSource()
      }

    case CollectSource() =>
      val incomingFile = datasetRepo.createIncomingFile(s"$datasetRepo-${System.currentTimeMillis()}.xml")
      val progressReporter = ProgressReporter(COLLECTING, datasetRepo.datasetDb)
      val recordCount = harvestRepo.generateSourceFile(incomingFile, datasetRepo.datasetDb.setNamespaceMap, progressReporter)
      datasetRepo.datasetDb.setStatus(READY)
      val info = datasetRepo.datasetDb.getDatasetInfoOption.get
      val delimit = info \ "delimit"
      val recordRoot = (delimit \ "recordRoot").text
      val uniqueId = (delimit \ "uniqueId").text
      datasetRepo.datasetDb.setRecordDelimiter(recordRoot, uniqueId, recordCount)
      context.stop(self)


  }
}








