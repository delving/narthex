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

package record

import java.io.File

import akka.actor.{Actor, Props}
import dataset.DatasetActor.InterruptWork
import dataset.DatasetRepo
import dataset.ProgressState.{SAVING, UPDATING}
import org.basex.core.cmd.Optimize
import org.joda.time.DateTime
import play.api.Logger
import record.RecordHandling.RawRecord
import record.Saver.{SaveComplete, SaveRecords}
import services.{FileHandling, ProgressReporter}

import scala.concurrent._
import scala.language.postfixOps

object Saver {

  case class SaveRecords(modifiedAfter: Option[DateTime], fileOption: Option[File], recordRoot: String, uniqueId: String, recordCount: Long, deepRecordContainer: Option[String])

  case class SaveComplete(errorOption: Option[String] = None)

  def props(datasetRepo: DatasetRepo) = Props(new Saver(datasetRepo))
}

class Saver(val datasetRepo: DatasetRepo) extends Actor with RecordHandling {

  import context.dispatcher

  var log = Logger
  var progress: Option[ProgressReporter] = None

  def receive = {

    case InterruptWork() =>
      progress.map(_.bomb = Some(sender)).getOrElse(context.stop(self))

    case SaveRecords(modifiedAfter: Option[DateTime], fileOption: Option[File], recordRoot, uniqueId, recordCount, deepRecordContainer) =>
      log.info(s"Saving $datasetRepo modified=$modifiedAfter")
      modifiedAfter.map { modified =>
        val file = fileOption.get
        val parser = new RawRecordParser(recordRoot, uniqueId, deepRecordContainer)
        val (source, readProgress) = FileHandling.sourceFromFile(file)
        val progressReporter = ProgressReporter(UPDATING, datasetRepo.datasetDb)
        progressReporter.setReadProgress(readProgress)
        future {
          datasetRepo.recordDb.db { session =>
            def receiveRecord(record: RawRecord) = {
              // todo: replace existing record!
              log.info(s"UPDATE ${record.id}")
            }
            try {
              parser.parse(source, Set.empty[String], receiveRecord, progressReporter)
            }
            catch {
              case e: Exception =>
                log.error(s"Unable to update $datasetRepo", e)
                context.parent ! SaveComplete(Some(e.toString))
            }
            finally {
              source.close()
            }
          }
        }
      } getOrElse {
        datasetRepo.getLatestIncomingFile.map { incomingFile =>
          datasetRepo.recordDb.createDb()
          var tick = 0
          var time = System.currentTimeMillis()
          future {
            datasetRepo.recordDb.db { session =>
              val parser = new RawRecordParser(recordRoot, uniqueId, deepRecordContainer)
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
                  log.info(s"Saved ${datasetRepo.analyzedDir.getName}, optimizing..")
                  datasetRepo.recordDb.db(_.execute(new Optimize()))
                  datasetRepo.datasetDb.setNamespaceMap(parser.namespaceMap)
                  context.parent ! SaveComplete()
                }
                else {
                  context.parent ! SaveComplete(Some("Interrupted while saving"))
                }
              }
              catch {
                case e: Exception =>
                  log.error(s"Unable to save $datasetRepo", e)
                  context.parent ! SaveComplete(Some(e.toString))
              }
              finally {
                source.close()
              }
            }
          }
        }
      }
  }
}

