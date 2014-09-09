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
      datasetRepo.recordRepo.createDb
      datasetRepo.recordRepo.db {
        session =>
          parser = new RawRecordParser(recordRoot, uniqueId)
          val (source, readProgress) = FileHandling.xmlSource(datasetRepo.sourceFile)

          val progress = context.actorOf(Props(new Actor() {
            override def receive: Receive = {
              case SaveProgress(percent) =>
                if (percent == 100) context.stop(self)
                datasetRepo.datasetDb.setStatus(SAVING, percent = percent)
            }
          }))

          def sendProgress(percent: Int) = progress ! SaveProgress(percent)

          def receiveRecord(record: String) = session.add(hashRecordFileName(collection, record), bytesOf(record))

          parser.parse(source, receiveRecord, recordCount, sendProgress)
          sendProgress(100)
          source.close()
          self ! SaveComplete()
      }

    case SaveComplete() =>
      Logger.info(s"Saved ${datasetRepo.dir.getName}, optimizing..")
      datasetRepo.recordRepo.db(_.execute(new Optimize()))
      Logger.info(s"Optimized ${datasetRepo.dir.getName}.")
      datasetRepo.datasetDb.setNamespaceMap(parser.namespaceMap)
      datasetRepo.datasetDb.setStatus(SAVED)
      context.stop(self)

  }
}

case class SaveRecords(recordRoot: String, uniqueId: String, recordCount: Int, collection: String)

case class SaveProgress(percent: Int)

case class SaveComplete()
