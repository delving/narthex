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

import actors.OrgSupervisor.ActorShutdown
import actors.Saver._
import akka.actor.{Actor, Props}
import org.basex.core.cmd.Optimize
import play.api.Logger
import services.DatasetState._
import services.RecordHandling.RawRecord
import services.{DatasetRepo, FileHandling, RecordHandling}

import scala.concurrent.Future
import scala.language.postfixOps

object Saver {

  case class SaveRecords(recordRoot: String, uniqueId: String, recordCount: Long, deepRecordContainer: Option[String])

  case class SaveProgress(percent: Int)

  case class SaveComplete()

  case class SaveError(error: String)

  def props(datasetRepo: DatasetRepo) = Props(new Saver(datasetRepo))
}

class Saver(val datasetRepo: DatasetRepo) extends Actor with RecordHandling {
  var log = Logger
  var parser: RawRecordParser = null
  var progress : Option[ProgressReporter] = None

  def receive = {

    case ActorShutdown(name) =>
      progress.foreach(_.bomb = Some(sender))

    case SaveRecords(recordRoot, uniqueId, recordCount, deepRecordContainer) =>
      log.info(s"Saving $datasetRepo")
      datasetRepo.getLatestIncomingFile.map { incomingFile =>
        datasetRepo.recordDb.createDb
        var tick = 0
        var time = System.currentTimeMillis()
        import context.dispatcher
        val f = Future(datasetRepo.recordDb.db {
          session =>
            parser = new RawRecordParser(recordRoot, uniqueId, deepRecordContainer)
            val (source, readProgress) = FileHandling.sourceFromFile(incomingFile)
            val progressReporter = ProgressReporter(SAVING, datasetRepo.datasetDb)
            progressReporter.setReadProgress(readProgress)
            def receiveRecord(record: RawRecord) = {
              tick += 1
              if (tick % 10000 == 0) {
                val now = System.currentTimeMillis()
                Logger.info(s"$datasetRepo $tick: ${now - time}ms")
                time = now
              }
              val hash = record.hash
              val fileName = s"${datasetRepo.name}/${hash(0)}/${hash(1)}/${hash(2)}/$hash.xml"
              session.add(fileName, bytesOf(record.text))
            }
            try {
              if (parser.parse(source, Set.empty, receiveRecord, progressReporter)) {
                self ! SaveComplete()
              }
              else {
                self ! SaveError("Interrupted")
              }
            }
            catch {
              case e: Exception =>
                log.error(s"Unable to save $datasetRepo", e)
                self ! SaveError(e.toString)
            }
            finally {
              source.close()
            }
        })
      }

    case SaveError(error) =>
      datasetRepo.datasetDb.setStatus(ANALYZED, error = error)
      context.stop(self)

    case SaveComplete() =>
      log.info(s"Saved ${datasetRepo.analyzedDir.getName}, optimizing..")
      datasetRepo.recordDb.db(_.execute(new Optimize()))
      log.info(s"Optimized ${datasetRepo.analyzedDir.getName}.")
      datasetRepo.datasetDb.setNamespaceMap(parser.namespaceMap)
      datasetRepo.datasetDb.setStatus(SAVED)
      context.stop(self)


  }
}

