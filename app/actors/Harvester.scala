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
import akka.actor.{Actor, ActorLogging, Props}
import akka.pattern.pipe
import org.apache.commons.io.FileUtils._
import services.DatasetState._
import services.{DatasetRepo, Harvesting, RecordHandling}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps

object Harvester {

  case class HarvestAdLib(url: String, database: String)

  case class HarvestPMH(url: String, set: String, prefix: String)

  case class HarvestProgress(percent: Int)

  case class HarvestComplete()

  def props(datasetRepo: DatasetRepo) = Props(new Harvester(datasetRepo))

  global.getClass // to avoid optimizing the import away
}

class Harvester(val datasetRepo: DatasetRepo) extends Actor with RecordHandling with Harvesting with ActorLogging {

  var tempFile = File.createTempFile("narthex","harvest")
  val zip = new ZipOutputStream(new FileOutputStream(tempFile))
  var pageCount = 0

  def addPage(page: String) = {
    pageCount = pageCount + 1
    val fileName = s"harvest_$pageCount.xml"
    zip.putNextEntry(new ZipEntry(fileName))
    zip.write(page.getBytes("UTF-8"))
    zip.closeEntry()
    fileName
  }

  def renameFile() = {
    zip.close()
    deleteQuietly(datasetRepo.sourceFile)
    moveFile(tempFile, datasetRepo.sourceFile)
  }

  def receive = {

    case HarvestAdLib(url, database) =>
      log.info(s"Harvesting $url $database to $datasetRepo")
      datasetRepo.datasetDb.setHarvestInfo("adlib", url, database, "adlib")
      fetchAdLibPage(url, database) pipeTo self

    case AdLibHarvestPage(records, url, database, diagnostic) =>
      val pageName = addPage(records)
      log.info(s"Page: $pageName - $url $database to $datasetRepo: $diagnostic")
      if (diagnostic.isLast) {
        self ! HarvestComplete()
      }
      else {
        datasetRepo.datasetDb.setStatus(HARVESTING, percent = diagnostic.percentComplete)
        fetchAdLibPage(url, database, Some(diagnostic)) pipeTo self
      }

    case HarvestPMH(url, set, prefix) =>
      log.info(s"Harvesting $url $set $prefix to $datasetRepo")
      datasetRepo.datasetDb.setHarvestInfo("pmh", url, set, prefix)
      fetchPMHPage(url, set, prefix) pipeTo self

    case PMHHarvestPage(records, url, set, prefix, total, resumptionToken) =>
      val pageName = addPage(records)
      log.info(s"Page $pageName to $datasetRepo: $resumptionToken")
      resumptionToken match {
        case None =>
          self ! HarvestComplete()
        case Some(token) =>
          datasetRepo.datasetDb.setStatus(HARVESTING, percent = token.percentComplete)
          fetchPMHPage(url, set, prefix, Some(token)) pipeTo self
      }

    case HarvestComplete() =>
      log.info(s"Harvested $datasetRepo")
      renameFile()
      datasetRepo.datasetDb.setStatus(READY)
      context.stop(self)

  }
}








