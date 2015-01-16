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
import dataset.DatasetActor.{InterruptWork, WorkFailure}
import dataset.DatasetRepo
import harvest.Harvester.{HarvestAdLib, HarvestComplete, HarvestPMH, IncrementalHarvest}
import harvest.Harvesting.{AdLibHarvestPage, HarvestError, PMHHarvestPage}
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import play.api.Logger
import services.ProgressReporter
import services.ProgressReporter.ProgressState._

import scala.concurrent._
import scala.language.postfixOps

object Harvester {

  case class HarvestAdLib(url: String, database: String, search: String, modifiedAfter: Option[DateTime])

  case class HarvestPMH(url: String, set: String, prefix: String, modifiedAfter: Option[DateTime], justDate: Boolean)

  case class IncrementalHarvest(modifiedAfter: DateTime, fileOpt: Option[File])

  case class HarvestComplete(incrementalOpt: Option[IncrementalHarvest])

  def props(datasetRepo: DatasetRepo) = Props(new Harvester(datasetRepo))
}

class Harvester(val datasetRepo: DatasetRepo) extends Actor with Harvesting {

  import context.dispatcher

  val db = datasetRepo.datasetDb
  val log = Logger
  var tempFile = File.createTempFile("narthex-harvest", ".zip")
  val zip = new ZipOutputStream(new FileOutputStream(tempFile))
  var pageCount = 0
  var progress: Option[ProgressReporter] = None

  def addPage(page: String): Int = {
    val harvestPageName = s"harvest_$pageCount.xml"
    zip.putNextEntry(new ZipEntry(harvestPageName))
    zip.write(page.getBytes("UTF-8"))
    zip.closeEntry()
    pageCount += 1
    pageCount
  }

  def finish(modifiedAfter: Option[DateTime], error: Option[String]) = {
    zip.close()
    log.info(s"finished harvest modified=$modifiedAfter error=$error")
    error.map { errorString =>
      FileUtils.deleteQuietly(tempFile)
      context.parent ! WorkFailure(errorString)
    } getOrElse {
      datasetRepo.sourceRepoOpt.map { sourceRepo =>
        future {
          val acceptZipReporter = ProgressReporter(COLLECTING, context.parent)
          val fileOption = sourceRepo.acceptFile(tempFile, acceptZipReporter)
          log.info(s"Zip file accepted: $fileOption")
          val incrementalOpt = modifiedAfter.map(IncrementalHarvest(_, fileOption))
          context.parent ! HarvestComplete(incrementalOpt)
        } onFailure {
          case e: Exception =>
            context.parent ! WorkFailure(e.getMessage)
        }
      } getOrElse {
        context.parent ! WorkFailure(s"No source repo for $datasetRepo")
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

    case InterruptWork =>
      if (!progress.exists(_.interruptBy(sender()))) context.stop(self)

    case HarvestAdLib(url, database, search, modifiedAfter) =>
      log.info(s"Harvesting $url $database to $datasetRepo")
      val futurePage = fetchAdLibPage(url, database, search, modifiedAfter)
      handleFailure(futurePage, modifiedAfter, "adlib harvest")
      progress = Some(ProgressReporter(HARVESTING, context.parent))
      futurePage pipeTo self

    case AdLibHarvestPage(records, url, database, search, modifiedAfter, diagnostic) =>
      progress.foreach { progressReporter =>
        val pageNumber = addPage(records)
        val keepGoing = if (modifiedAfter.isDefined)
          progressReporter.sendPage(pageNumber) // This compensates for AdLib's failure to report number of hits
        else
          progressReporter.sendPercent(diagnostic.percentComplete)
        if (keepGoing) {
          log.info(s"Harvest Page: $pageNumber - $url $database to $datasetRepo: $diagnostic")
          if (diagnostic.isLast) {
            finish(modifiedAfter, None)
          }
          else {
            val futurePage = fetchAdLibPage(url, database, search, modifiedAfter, Some(diagnostic))
            handleFailure(futurePage, modifiedAfter, "adlib harvest page")
            futurePage pipeTo self
          }
        }
        else {
          finish(modifiedAfter, Some(s"Interrupted while processing $datasetRepo"))
        }
      }

    case HarvestPMH(url, set, prefix, modifiedAfter, justDate) =>
      log.info(s"Harvesting $url $set $prefix to $datasetRepo")
      progress = Some(ProgressReporter(HARVESTING, context.parent))
      val futurePage = fetchPMHPage(url, set, prefix, modifiedAfter, justDate)
      handleFailure(futurePage, modifiedAfter, "pmh harvest")
      // todo: if none comes back there's something wrong
      futurePage pipeTo self

    case PMHHarvestPage(records, url, set, prefix, total, modifiedAfter, justDate, resumptionToken) =>
      val pageNumber = addPage(records)
      log.info(s"Harvest Page $pageNumber to $datasetRepo: $resumptionToken")
      progress.foreach { progressReporter =>
        resumptionToken.map { token =>
          val keepHarvesting = if (token.hasPercentComplete) {
            progressReporter.sendPercent(token.percentComplete)
          }
          else {
            progressReporter.sendPage(pageCount)
          }
          if (keepHarvesting) {
            val futurePage = fetchPMHPage(url, set, prefix, modifiedAfter, justDate, resumptionToken)
            handleFailure(futurePage, modifiedAfter, "pmh harvest page")
            futurePage pipeTo self
          }
          else {
            finish(modifiedAfter, Some("Interrupted while harvesting"))
          }
        } getOrElse {
          finish(modifiedAfter, None)
        }
      }

    case HarvestError(error, modifiedAfter) =>
      finish(modifiedAfter, Some(error))

  }
}