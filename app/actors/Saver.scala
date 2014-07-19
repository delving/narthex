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

import java.io.ByteArrayInputStream

import akka.actor.{Actor, ActorLogging, Props}
import play.Logger
import services.{FileHandling, FileRepo, RecordHandling}

import scala.language.postfixOps

object Saver {
  def props(fileRepo: FileRepo) = Props(new Saver(fileRepo))
}

class Saver(val fileRepo: FileRepo) extends Actor with RecordHandling with ActorLogging {

  def receive = {

    case SaveRecords(recordRoot, uniqueId, recordCount, collection) =>
      fileRepo.withNewRecordDatabase {
        session =>
          val parser = new RecordParser(recordRoot, uniqueId)
          val source = FileHandling.source(fileRepo.sourceFile)

          val progress = context.actorOf(Props(new Actor() {
            override def receive: Receive = {
              case SaveProgress(percent) =>
                fileRepo.setStatus(fileRepo.SAVING, percent, 0)
            }
          }))

          def sendProgress(percent: Int) = progress ! SaveProgress(percent)

          def receiveRecord(record: String) = {
            val hash = hashString(record)
            val inputStream = new ByteArrayInputStream(record.getBytes("UTF-8"))
            session.add(s"$collection/${hash(0)}/${hash(1)}/${hash(2)}/$hash.xml", inputStream)
          }

          parser.parse(source, receiveRecord, recordCount, sendProgress)
          source.close()
          context.stop(progress)
          self ! SaveComplete()
      }

    case SaveComplete() =>
      Logger.info(s"Save complete")
      fileRepo.setStatus(fileRepo.SAVED, 0, 0)
      context.stop(self)

  }
}

case class SaveRecords(recordRoot: String, uniqueId: String, recordCount: Int, collection: String)

case class SaveProgress(percent: Int)

case class SaveComplete()


