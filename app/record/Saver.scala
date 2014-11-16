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
import org.basex.core.cmd.{Delete, Optimize}
import org.joda.time.DateTime
import play.api.Logger
import record.PocketParser.Pocket
import record.Saver.{SaveComplete, SaveRecords}
import services.SipFile.SipMapper
import services.{FileHandling, ProgressReporter}

import scala.concurrent._
import scala.language.postfixOps

object Saver {

  case class SaveRecords(modifiedAfter: Option[DateTime], file: File,
                         recordRoot: String, uniqueId: String, recordCount: Long, deepRecordContainer: Option[String],
                         sipMapper: Option[SipMapper])

  case class SaveComplete(errorOption: Option[String] = None)

  case class MappingContext(recordDb: RecordDb, sipMapper: Option[SipMapper])

  def props(datasetRepo: DatasetRepo) = Props(new Saver(datasetRepo))

}

class Saver(val datasetRepo: DatasetRepo) extends Actor {

  import context.dispatcher

  var log = Logger
  var progress: Option[ProgressReporter] = None

  def receive = {

    case InterruptWork() =>
      progress.map(_.bomb = Some(sender())).getOrElse(context.stop(self))

    case SaveRecords(modifiedAfter: Option[DateTime], file, recordRoot, uniqueId, recordCount, deepRecordContainer, sipMapper) =>
      log.info(s"Saving $datasetRepo modified=$modifiedAfter file=${file.getAbsolutePath})")
      val recordDb = datasetRepo.recordDbOpt.getOrElse(throw new RuntimeException(s"Expected record db for $datasetRepo"))

      val f: Future[Unit] = future {

        modifiedAfter.map { after =>
          val parser = new PocketParser(recordRoot, uniqueId, deepRecordContainer)
          val (source, readProgress) = FileHandling.sourceFromFile(file)
          val progressReporter = ProgressReporter(UPDATING, datasetRepo.datasetDb)
          progressReporter.setReadProgress(readProgress)
          progress = Some(progressReporter)
          recordDb.withRecordDb { session =>
            def receiveRecord(rawPocket: Pocket): Unit = {
              val pocket = sipMapper.map(_.map(rawPocket)).getOrElse(rawPocket)
              log.info(s"Updating ${pocket.id}")
              recordDb.findRecord(pocket.id, session).map { foundRecord =>
                log.info(s"Record found $foundRecord, deleting it")
                if (pocket.hash == foundRecord.hash) {
                  log.info(s"The new record has the same hash, but ignoring that for now")
                }
                else {
                  log.info(s"The new record has a fresh hash")
                }
                session.execute(new Delete(foundRecord.path))
              }
              val path = datasetRepo.createPocketPath(pocket)
              log.info(s"Adding $path")
              session.add(path, pocket.textBytes)
            }
            try {
              parser.parse(source, Set.empty[String], receiveRecord, progressReporter)
              context.parent ! SaveComplete()
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
        } getOrElse {
          log.info(s"Saving file for $datasetRepo: ${file.getAbsolutePath}")
          recordDb.createDb()
          var tick = 0
          var time = System.currentTimeMillis()
          recordDb.withRecordDb { session =>
            val parser = new PocketParser(recordRoot, uniqueId, deepRecordContainer)
            val (source, readProgress) = FileHandling.sourceFromFile(file)
            val progressReporter = ProgressReporter(SAVING, datasetRepo.datasetDb)
            progressReporter.setReadProgress(readProgress)
            progress = Some(progressReporter)
            def receiveRecord(rawPocket: Pocket): Unit = {
              val pocket = sipMapper.map(_.map(rawPocket)).getOrElse(rawPocket)
              session.add(datasetRepo.createPocketPath(pocket), pocket.textBytes)
              tick += 1
              if (tick % 10000 == 0) {
                val now = System.currentTimeMillis()
                Logger.info(s"$datasetRepo $tick: ${now - time}ms")
                time = now
              }
            }
            try {
              if (parser.parse(source, Set.empty, receiveRecord, progressReporter)) {
                log.info(s"Saved ${datasetRepo.analyzedDir.getName}, optimizing..")
                recordDb.withRecordDb(_.execute(new Optimize()))
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

