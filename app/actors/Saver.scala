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

import akka.actor.{Props, ActorLogging, Actor}
import scala.language.postfixOps
import play.Logger
import services.{FileRepo, RecordHandling, FileHandling}
import java.io.ByteArrayInputStream

object Saver {
  def props(fileRepo: FileRepo) = Props(new Saver(fileRepo))
}

class Saver(val fileRepo: FileRepo) extends Actor with RecordHandling with ActorLogging {

  def receive = {

    case SaveRecords(recordRoot, uniqueId, collection) =>
      fileRepo.personalRepo.withBaseX {
        session =>
          val parser = new RecordParser(recordRoot, uniqueId)
          val source = FileHandling.source(fileRepo.sourceFile)
          val totalRecords = 100 // todo: get this from the JSON file

          def sendProgress(percent: Int) = self ! SaveProgress(percent)

          def receiveRecord(record: String) = {
            val hash = hashString(record)
            val inputStream = new ByteArrayInputStream(record.getBytes("UTF-8"))
            session.add(s"$collection/$hash.xml", inputStream)
            Logger.info(s"stored $hash")
          }

          parser.parse(source, receiveRecord, totalRecords, sendProgress)
          source.close()
          self ! RecordsSaved()
      }

    case SaveProgress(count) =>
    // todo: record progress in an asset

    case RecordsSaved() =>
      // todo: save the fact that it is finished
      context.stop(self)

  }
}

case class SaveRecords(recordRoot: String, uniqueId: String, collection: String)

case class SaveProgress(percent: Int)

case class RecordsSaved()


