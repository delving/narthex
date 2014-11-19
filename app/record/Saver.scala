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
import dataset.ProgressState._
import harvest.HarvestRepo
import harvest.Harvesting.HarvestType
import org.basex.core.cmd.{Delete, Optimize}
import org.joda.time.DateTime
import play.api.Logger
import record.PocketParser.Pocket
import record.Saver._
import services.SipFile.SipMapper
import services.{FileHandling, ProgressReporter}

import scala.concurrent._
import scala.language.postfixOps

object Saver {

  case class GenerateSourceFromHarvest()

  case class GenerateSourceFromSipFile()

  case class GenerationComplete(file: File, recordCount: Int)

  case class SaveRecords(modifiedAfter: Option[DateTime], file: File,
                         recordRoot: String, uniqueId: String, recordCount: Long, deepRecordContainer: Option[String],
                         sipMapperOpt: Option[SipMapper])

  case class SaveComplete(errorOption: Option[String] = None)

  case class MappingContext(recordDb: RecordDb, sipMapper: Option[SipMapper])

  def props(datasetRepo: DatasetRepo) = Props(new Saver(datasetRepo))

}

class Saver(val datasetRepo: DatasetRepo) extends Actor {

  import context.dispatcher

  var log = Logger
  var progress: Option[ProgressReporter] = None
  val db = datasetRepo.datasetDb

  def receive = {

    case InterruptWork() =>
      progress.map(_.bomb = Some(sender())).getOrElse(context.stop(self))

    case SaveRecords(modifiedAfter: Option[DateTime], file, recordRoot, uniqueId, recordCount, deepRecordContainer, sipMapperOpt) =>
      log.info(s"Saving $datasetRepo modified=$modifiedAfter file=${file.getAbsolutePath}) root=$recordRoot deepRoot=$deepRecordContainer")
      val recordDb = datasetRepo.recordDbOpt.getOrElse(throw new RuntimeException(s"Expected record db for $datasetRepo"))

      future {

        modifiedAfter.map { after =>
          val parser = new PocketParser(recordRoot, uniqueId, deepRecordContainer)
          val (source, readProgress) = FileHandling.sourceFromFile(file)
          val progressReporter = ProgressReporter(UPDATING, datasetRepo.datasetDb)
          progressReporter.setReadProgress(readProgress)
          progress = Some(progressReporter)
          recordDb.withRecordDb { session =>
            def receiveRecord(rawPocket: Pocket): Unit = {
              val pocketOpt = sipMapperOpt.map(_.map(rawPocket)).getOrElse(Some(rawPocket))
              pocketOpt.map { pocket =>
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
              val pocketOpt = sipMapperOpt.map(_.map(rawPocket)).getOrElse(Some(rawPocket))
              pocketOpt.map { pocket =>
                session.add(datasetRepo.createPocketPath(pocket), pocket.textBytes)
                tick += 1
                if (tick % 10000 == 0) {
                  val now = System.currentTimeMillis()
                  Logger.info(s"$datasetRepo $tick: ${now - time}ms")
                  time = now
                }
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

    case GenerateSourceFromHarvest() =>
      log.info(s"Generate source from harvest $datasetRepo")
      future {
        db.infoOpt.foreach { info =>
          HarvestType.harvestTypeFromInfo(info).map { harvestType =>
            val harvestRepo = new HarvestRepo(datasetRepo.harvestDir, harvestType)
            val incomingFile = datasetRepo.createIncomingFile(s"$datasetRepo.xml")
            val generateSourceReporter = ProgressReporter(GENERATING, db)
            progress = Some(generateSourceReporter)
            val sipMapperOpt = datasetRepo.sipRepo.latestSipFile.flatMap(_.createSipMapper)
            val newRecordCount = harvestRepo.generateSourceFile(incomingFile, sipMapperOpt, db.setNamespaceMap, generateSourceReporter)
            datasetRepo.recordDbOpt.foreach { recordDb =>
              val existingRecordCount = recordDb.getRecordCount
              log.info(s"Collected source records from $existingRecordCount to $newRecordCount")
              // set record count
              val delimit = info \ "delimit"
              val recordRoot = (delimit \ "recordRoot").text
              val uniqueId = (delimit \ "uniqueId").text
              db.setRecordDelimiter(recordRoot, uniqueId, newRecordCount)
            }
            context.parent ! GenerationComplete(incomingFile, newRecordCount)
          }
        }
      }

    case GenerateSourceFromSipFile() =>
      log.info(s"Generate source from sip file $datasetRepo")
      future {
        datasetRepo.sipRepo.latestSipFile.map { sipFile =>
          try {
            val incomingFile = datasetRepo.createIncomingFile(s"$datasetRepo.xml")
            val generateSourceReporter = ProgressReporter(GENERATING, db)
            progress = Some(generateSourceReporter)
            val newRecordCount = sipFile.generateSourceFile(incomingFile, generateSourceReporter)
            datasetRepo.datasetDb.endProgress(None)
            context.parent ! GenerationComplete(incomingFile, newRecordCount)
          }
          catch {
            case e:Exception => datasetRepo.datasetDb.endProgress(Some(e.toString))
          }
        }
      }
  }
}

