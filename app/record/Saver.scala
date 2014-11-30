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

import java.io.{File, FileOutputStream}

import akka.actor.{Actor, ActorLogging, Props}
import dataset.DatasetActor.{ChildFailure, IncrementalSave, InterruptWork}
import dataset.DatasetRepo
import dataset.ProgressState._
import dataset.Sip.SipMapper
import dataset.SipFactory.SipGenerationFacts
import org.basex.core.cmd.{Delete, Optimize}
import record.PocketParser.Pocket
import record.Saver._
import services.{FileHandling, ProgressReporter}

import scala.concurrent._
import scala.language.postfixOps

object Saver {

  case class AdoptSource(file: File)

  case class SourceAdoptionComplete(file: File)

  case class GenerateSource()

  case class SourceGenerationComplete(file: File, recordCount: Int)

  case class SaveRecords(incrementalOpt: Option[IncrementalSave])

  case class SaveComplete(recordCount: Int)

  case class MappingContext(recordDb: RecordDb, sipMapper: Option[SipMapper])

  def props(datasetRepo: DatasetRepo) = Props(new Saver(datasetRepo))

}

class Saver(val datasetRepo: DatasetRepo) extends Actor with ActorLogging {

  import context.dispatcher

  var progress: Option[ProgressReporter] = None
  val db = datasetRepo.datasetDb

  def receive = {

    case InterruptWork() =>
      if (!progress.exists(_.interruptBy(sender()))) context.stop(self)

    case AdoptSource(file) =>
      log.info(s"Adopt source ${file.getAbsolutePath}")
      datasetRepo.stagingRepoOpt.map { stagingRepo =>
        future {
          val progressReporter = ProgressReporter(ADOPTING, db)
          progress = Some(progressReporter)
          stagingRepo.acceptFile(file, progressReporter).map { adoptedFile =>
            context.parent ! SourceAdoptionComplete(adoptedFile)
          } getOrElse {
            context.parent ! ChildFailure(s"File not accepted: ${file.getAbsolutePath}")
          }
        } onFailure {
          case t => context.parent ! ChildFailure(t.getMessage, Some(t))
        }
      } getOrElse {
        context.parent ! ChildFailure("Missing staging repository!")
      }

    case GenerateSource() =>
      log.info("Generate source")
      datasetRepo.stagingRepoOpt.map { stagingRepo =>
        val sipMapperOpt = datasetRepo.sipMapperOpt
        future {
          val progressReporter = ProgressReporter(GENERATING, db)
          progress = Some(progressReporter)
          val pocketOutput = new FileOutputStream(datasetRepo.pocketFile)
          val recordCount = stagingRepo.generateSource(pocketOutput, datasetRepo.mappedFile, sipMapperOpt, progressReporter)
          pocketOutput.close()
          datasetRepo.sipRepo.latestSipOpt.map { latestSip =>
            latestSip.copyWithSourceTo(datasetRepo.sipFile, datasetRepo.pocketFile)
            //            FileUtils.deleteQuietly(datasetRepo.pocketFile)
          } getOrElse {
            val generationFactsOpt = db.infoOpt.map(SipGenerationFacts(_))
            generationFactsOpt.map { facts =>
              val sipPrefixRepo = datasetRepo.orgRepo.sipFactory.prefixRepo(facts.prefix)
              sipPrefixRepo.map { prefixRepo =>
                prefixRepo.generateSip(datasetRepo.sipFile, datasetRepo.pocketFile, facts)
                //            FileUtils.deleteQuietly(datasetRepo.pocketFile)
              } getOrElse {
                context.parent ! ChildFailure("Unable to build sip for download")
              }
            } getOrElse {
              context.parent ! ChildFailure("Unable to create sip generation facts")
            }
          }
          context.parent ! SourceGenerationComplete(datasetRepo.mappedFile, recordCount)
        } onFailure {
          case t => context.parent ! ChildFailure(t.getMessage, Some(t))
        }
      } getOrElse {
        context.parent ! ChildFailure("No data for generating source")
      }

    case SaveRecords(incrementalOpt) =>
      val stagingFacts = datasetRepo.stagingRepoOpt.map(_.stagingFacts).getOrElse(throw new RuntimeException(s"No staging facts for $datasetRepo"))
      log.info(s"Saving incremental=$incrementalOpt facts=$stagingFacts")
      val recordDb = datasetRepo.recordDbOpt.getOrElse(throw new RuntimeException(s"Expected record db for $datasetRepo"))
      val sipMapperOpt = datasetRepo.sipMapperOpt

      future {

        incrementalOpt.map { incremental =>
          val parser = PocketParser(stagingFacts)
          val (source, readProgress) = FileHandling.sourceFromFile(incremental.file)
          val progressReporter = ProgressReporter(UPDATING, datasetRepo.datasetDb)
          progressReporter.setReadProgress(readProgress)
          progress = Some(progressReporter)
          recordDb.withRecordDb { session =>
            var recordCount = 0
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
                session.add(pocket.path(datasetRepo.datasetName), pocket.textBytes)
                recordCount += 1
              }
            }
            try {
              parser.parse(source, Set.empty[String], receiveRecord, progressReporter)
              context.parent ! SaveComplete(recordCount)
            }
            finally {
              source.close()
            }
          }
        } getOrElse {
          log.info(s"Saving first time")
          recordDb.createDb()
          var tick = 0
          var time = System.currentTimeMillis()
          recordDb.withRecordDb { session =>
            datasetRepo.stagingRepoOpt.map { stagingRepo =>
              var recordCount = 0
              def catchPocket(rawPocket: Pocket): Unit = {
                val pocketOpt = sipMapperOpt.map(_.map(rawPocket)).getOrElse(Some(rawPocket))
                pocketOpt.map { pocket =>
                  session.add(pocket.path(datasetRepo.datasetName), pocket.textBytes)
                  tick += 1
                  if (tick % 10000 == 0) {
                    val now = System.currentTimeMillis()
                    log.info(s"Saving $tick: ${now - time}ms")
                    time = now
                  }
                  recordCount += 1
                }
              }
              val progressReporter = ProgressReporter(SAVING, datasetRepo.datasetDb)
              progress = Some(progressReporter)
              stagingRepo.parsePockets(catchPocket, progressReporter)
              if (progress.isDefined) {
                log.info(s"Saved, optimizing..")
                recordDb.withRecordDb(_.execute(new Optimize()))
                context.parent ! SaveComplete(recordCount)
              }
              else {
                context.parent ! ChildFailure("Interrupted while saving")
              }
            }
          }
        }
      } onFailure {
        case t => context.parent ! ChildFailure(t.getMessage, Some(t))
      }
  }

}

