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

import akka.actor.{Actor, Props}
import akka.pattern.pipe
import dataset.DatasetActor.InterruptWork
import dataset.DatasetRepo
import dataset.ProgressState._
import harvest.Harvester.{HarvestAdLib, HarvestComplete, HarvestPMH}
import harvest.Harvesting.{AdLibHarvestPage, PMHHarvestPage}
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import play.api.Logger
import record.RecordHandling
import services.ProgressReporter

import scala.concurrent._
import scala.language.postfixOps

object Harvester {

  case class HarvestAdLib(url: String, database: String, modifiedAfter: Option[DateTime])

  case class HarvestPMH(url: String, set: String, prefix: String, modifiedAfter: Option[DateTime])

  case class HarvestComplete(modifiedAfter: Option[DateTime], file: Option[File], error:Option[String])


  def props(datasetRepo: DatasetRepo, harvestRepo: HarvestRepo) = Props(new Harvester(datasetRepo, harvestRepo))
}

class Harvester(val datasetRepo: DatasetRepo, harvestRepo: HarvestRepo) extends Actor with RecordHandling with Harvesting {
  import context.dispatcher
  val db = datasetRepo.datasetDb
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

  def finish(modifiedAfter: Option[DateTime], error: Option[String]) = {
    zip.close()
    log.info(s"finished harvest modified=$modifiedAfter error=$error")
    error match {
      case Some(errorString) =>
        FileUtils.deleteQuietly(tempFile)
        context.parent ! HarvestComplete(modifiedAfter, file = None, error = Some(errorString))
      case None =>
        val completion = future {
          val acceptZipReporter = ProgressReporter(COLLECTING, db)
          val fileOption = harvestRepo.acceptZipFile(tempFile, acceptZipReporter)
          if (fileOption.isDefined) { // new harvest so there's work to do
            val incomingFile = datasetRepo.createIncomingFile(s"$datasetRepo-${System.currentTimeMillis()}.xml")
            val generateSourceReporter = ProgressReporter(GENERATING, db)
            val newRecordCount = harvestRepo.generateSourceFile(incomingFile, db.setNamespaceMap, generateSourceReporter)
            val existingRecordCount = datasetRepo.recordDb.getRecordCount
            log.info(s"Collected source records from $existingRecordCount to $newRecordCount")
            // set record count
            val info = db.infoOption.get
            val delimit = info \ "delimit"
            val recordRoot = (delimit \ "recordRoot").text
            val uniqueId = (delimit \ "uniqueId").text
            db.setRecordDelimiter(recordRoot, uniqueId, newRecordCount)
          }
          context.parent ! HarvestComplete(modifiedAfter, fileOption, None)
        }
        completion.onFailure {
          case e: Exception =>
            context.parent ! HarvestComplete(modifiedAfter, file = None, error = Some(e.toString))
        }
    }
  }

  def handleFailure(future: Future[Any], modifiedAfter: Option[DateTime], message: String) = {
    future.onFailure {
      case e: Exception =>
        log.warn(s"Harvest failure", e)
        finish(modifiedAfter, Some(e.toString))
    }
  }

  def receive = {

    case InterruptWork() =>
      log.info(s"Interrupt harvesting $datasetRepo")
      harvestProgress.map(_.bomb = Some(sender)).getOrElse(context.stop(self))

    case HarvestAdLib(url, database, modifiedAfter) =>
      log.info(s"Harvesting $url $database to $datasetRepo")
      val futurePage = fetchAdLibPage(url, database, modifiedAfter)
      handleFailure(futurePage, modifiedAfter, "adlib harvest")
      harvestProgress = Some(ProgressReporter(HARVESTING, db))
      futurePage pipeTo self

    case AdLibHarvestPage(records, error: Option[String], url, database, modifiedAfter, diagnostic) =>
      if (error.isDefined) {
        finish(modifiedAfter, error)
      }
      else harvestProgress.foreach { progressReporter =>
        val pageNumber = addPage(records)
        if (progressReporter.sendPercent(diagnostic.percentComplete)) {
          log.info(s"Harvest Page: $pageNumber - $url $database to $datasetRepo: $diagnostic")
          if (diagnostic.isLast) {
            finish(modifiedAfter, None)
          }
          else {
            val futurePage = fetchAdLibPage(url, database, modifiedAfter, Some(diagnostic))
            handleFailure(futurePage, modifiedAfter, "adlib harvest page")
            futurePage pipeTo self
          }
        }
        else {
          finish(modifiedAfter, Some(s"Interrupted while processing $datasetRepo"))
        }
      }

    case HarvestPMH(url, set, prefix, modifiedAfter) =>
      log.info(s"Harvesting $url $set $prefix to $datasetRepo")
      harvestProgress = Some(ProgressReporter(HARVESTING, db))
      val futurePage = fetchPMHPage(url, set, prefix, modifiedAfter)
      handleFailure(futurePage, modifiedAfter, "pmh harvest")
      futurePage pipeTo self

    case PMHHarvestPage(records, url, set, prefix, total, modifiedAfter, error, resumptionToken) =>
      if (error.isDefined) {
        finish(modifiedAfter, error)
      }
      else {
        val pageNumber = addPage(records)
        log.info(s"Harvest Page $pageNumber to $datasetRepo: $resumptionToken")
        harvestProgress.foreach { progressReporter =>
          resumptionToken match {
            case None =>
              finish(modifiedAfter, None)
            case Some(token) =>
              val keepHarvesting = if (token.hasPercentComplete) {
                progressReporter.sendPercent(token.percentComplete)
              }
              else {
                progressReporter.sendPage(pageCount)
              }
              if (keepHarvesting) {
                val futurePage = fetchPMHPage(url, set, prefix, None, Some(token))
                handleFailure(futurePage, modifiedAfter, "pmh harvest page")
                futurePage pipeTo self
              }
              else {
                finish(modifiedAfter, Some("Interrupted while harvesting"))
              }
          }
        }
      }
  }
}