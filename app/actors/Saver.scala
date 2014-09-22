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
import org.basex.core.cmd.Optimize
import play.Logger
import services.DatasetState._
import services.{DatasetRepo, FileHandling, RecordHandling}

import scala.language.postfixOps

object Saver {
  def props(datasetRepo: DatasetRepo) = Props(new Saver(datasetRepo))
}

class Saver(val datasetRepo: DatasetRepo) extends Actor with RecordHandling with ActorLogging {

  var parser: RawRecordParser = null

  def receive = {

    case SaveRecords(recordRoot, uniqueId, recordCount, collection) =>
      Logger.info(s"Saving $datasetRepo")
      datasetRepo.recordDb.createDb
      var tick = 0
      var time = System.currentTimeMillis()
      datasetRepo.recordDb.db {
        session =>
          parser = new RawRecordParser(recordRoot, uniqueId)
          val (source, readProgress) = FileHandling.xmlSource(datasetRepo.sourceFile)
          val progress = context.actorOf(Props(new Actor() {
            override def receive: Receive = {
              case SaveProgress(percent) =>
                datasetRepo.datasetDb.setStatus(SAVING, percent = percent)
            }
          }))
          def sendProgress(percent: Int) = progress ! SaveProgress(percent)
          def receiveRecord(record: String) = {
            tick += 1
            if (tick % 100000 == 0) {
              val now = System.currentTimeMillis()
              Logger.info(s"$datasetRepo $tick: ${now - time}ms")
              time = now
            }
            session.add(hashRecordFileName(collection, record), bytesOf(record))
          }
          try {
            parser.parse(source, receiveRecord, recordCount, sendProgress)
            self ! SaveComplete()
          }
          catch {
            case e:Exception =>
              Logger.error("Problem saving", e)
              datasetRepo.datasetDb.setStatus(ANALYZED, error = e.toString)
              context.stop(self)
          }
          finally {
            source.close()
            context.stop(progress)
          }
      }

    case SaveComplete() =>
      Logger.info(s"Saved ${datasetRepo.dir.getName}, optimizing..")
      datasetRepo.recordDb.db(_.execute(new Optimize()))
      Logger.info(s"Optimized ${datasetRepo.dir.getName}.")
      datasetRepo.datasetDb.setNamespaceMap(parser.namespaceMap)
      datasetRepo.datasetDb.setStatus(SAVED)
      context.stop(self)

  }
}

case class SaveRecords(recordRoot: String, uniqueId: String, recordCount: Int, collection: String)

case class SaveProgress(percent: Int)

case class SaveComplete()
