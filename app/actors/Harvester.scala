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
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.pipe
import org.apache.commons.io.FileUtils
import services.{FileRepo, Harvesting, RecordHandling}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps

object Harvester {

  case class HarvestAdLib(url: String, database: String)

  case class HarvestPMH(url: String, set: String, metadataPrefix: String)

  case class HarvestProgress(percent: Int)

  case class HarvestComplete()

  def props(fileRepo: FileRepo) = Props(new Harvester(fileRepo))

  global.getClass // to avoid optimizing the import away
}

class Harvester(val fileRepo: FileRepo) extends Actor with RecordHandling with Harvesting with ActorLogging {

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
    FileUtils.moveFile(tempFile, fileRepo.sourceFile)
  }

  var boss : ActorRef = null

  def receive = {

    case HarvestAdLib(url, database) =>
      log.info(s"Harvesting $url $database to $fileRepo")
      boss = sender
      fetchAdLibPage(url, database) pipeTo self

    case AdLibHarvestPage(records, url, database, diagnostic) =>
      val pageName = addPage(records)
      log.info(s"Page: $pageName - $url $database to $fileRepo: $diagnostic")
      if (diagnostic.isLast) {
        self ! HarvestComplete()
      }
      else {
        fetchAdLibPage(url, database, Some(diagnostic)) pipeTo self
      }

    case HarvestPMH(url, set, metadataPrefix) =>
      log.info(s"Harvesting $url $set $metadataPrefix to $fileRepo")
      boss = sender
      fetchPMHPage(url, set, metadataPrefix) pipeTo self

    case PMHHarvestPage(records, url, set, prefix, total, resumptionToken) =>
      val pageName = addPage(records)
      log.info(s"Page $pageName to $fileRepo: $resumptionToken")
      resumptionToken match {
        case None =>
          self ! HarvestComplete()
        case Some(token) =>
          fetchPMHPage(url, set, prefix, Some(token)) pipeTo self
      }

    case HarvestComplete() =>
      log.info(s"Harvested $fileRepo")
      renameFile()
      boss ! HarvestComplete()
      context.stop(self)

  }
}








