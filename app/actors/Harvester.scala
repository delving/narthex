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

import akka.actor.{Actor, ActorLogging, Props}
import akka.pattern.pipe
import org.basex.core.cmd.Optimize
import services.RepoUtil.State
import services.{FileRepo, Harvesting, RecordHandling}

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.io.Source
import scala.language.postfixOps

object Harvester {
  def props(fileRepo: FileRepo) = Props(new Harvester(fileRepo))
  global.getClass // to avoid optimizing the import away
}

class Harvester(val fileRepo: FileRepo) extends Actor with RecordHandling with Harvesting with ActorLogging {

  var parser: RawRecordParser = null
  val progress = context.actorOf(Props(new Actor() {
    override def receive: Receive = {
      case HarvestProgress(percent) =>
        println(s"Progress $percent") // todo
        if (percent == 100) context.stop(self)
        fileRepo.datasetDb.setStatus(State.SAVING, percent = percent)
    }
  }))

  def sendProgress(percent: Int) = progress ! HarvestProgress(percent)

  def receive = {

    case HarvestAdLib(url, database) =>
      println(s"Harvesting $url $database to ${fileRepo.dir.getName}")
      fileRepo.recordRepo.createDb
      parser = new RawRecordParser(ADLIB_RECORD_ROOT, ADLIB_UNIQUE_ID)
      fetchAdLibPage(url, database).pipeTo(self)

    case AdLibHarvestPage(records, url, database, diagnostic) =>
      println(s"Page $url $database to ${fileRepo.dir.getName}: $diagnostic")
      fileRepo.recordRepo.db {
        session =>
          def receiveRecord(record: String) = session.add(hashRecordFileName(fileRepo.name, record), bytesOf(record))
          val source = Source.fromString(records)
          parser.parse(source, receiveRecord, diagnostic.totalItems, sendProgress)
          source.close()
      }
      if (diagnostic.isLast) {
        self ! HarvestComplete()
      }
      else {
        fetchAdLibPage(url, database, Some(diagnostic)) pipeTo self
      }

    case HarvestPMH(url, set, metadataPrefix) =>
      println(s"Harvesting $url $set $metadataPrefix to ${fileRepo.dir.getName}")
      fileRepo.recordRepo.createDb
      parser = new RawRecordParser(PMH_RECORD_ROOT, PMH_UNIQUE_ID)
      fetchPMHPage(url, set, metadataPrefix) pipeTo self

    case PMHHarvestPage(records, url, set, prefix, total, resumptionToken) =>
      println(s"Page to ${fileRepo.dir.getName}: $resumptionToken")
      fileRepo.recordRepo.db {
        session =>
          val source = Source.fromString(records)
          def receiveRecord(record: String) = {
//            println(s"receive $record")
            session.add(hashRecordFileName(fileRepo.name, record), bytesOf(record))
          }
          parser.parse(source, receiveRecord, total, sendProgress)
          source.close()
      }
      resumptionToken match {
        case None =>
          self ! HarvestComplete()
        case Some(token) =>
          fetchPMHPage(url, set, prefix, Some(token)) pipeTo self
      }

    case HarvestComplete() =>
      println(s"Saved ${fileRepo.dir.getName}, optimizing..")
      sendProgress(100)
      fileRepo.recordRepo.db(_.execute(new Optimize()))
      println(s"Optimized ${fileRepo.dir.getName}.")
      fileRepo.datasetDb.setNamespaceMap(parser.namespaceMap)
      fileRepo.datasetDb.setStatus(State.SAVED)
      context.stop(self)

  }
}


case class HarvestAdLib(url: String, database: String)

case class HarvestPMH(url: String, set: String, metadataPrefix: String)

case class HarvestProgress(percent: Int)

case class HarvestComplete()







