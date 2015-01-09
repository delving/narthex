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
import dataset.DatasetActor.{IncrementalSave, InterruptWork, WorkFailure}
import dataset.DatasetRepo
import dataset.ProgressState._
import dataset.SipFactory.SipGenerationFacts
import org.apache.commons.io.FileUtils
import record.Saver._
import services.{FileHandling, ProgressReporter}

import scala.concurrent._
import scala.language.postfixOps

object Saver {

  case class AdoptSource(file: File)

  case class SourceAdoptionComplete(file: File)

  case object GenerateSource

  case class SourceGenerationComplete(rawRecordCount: Int, mappedRecordCount: Int)

  case class SaveRecords(incrementalOpt: Option[IncrementalSave])

  case class SaveComplete(recordCount: Int)

  def props(datasetRepo: DatasetRepo) = Props(new Saver(datasetRepo))

}

class Saver(val datasetRepo: DatasetRepo) extends Actor with ActorLogging {

  import context.dispatcher

  var progress: Option[ProgressReporter] = None
  val db = datasetRepo.datasetDb

  def receive = {

    case InterruptWork =>
      if (!progress.exists(_.interruptBy(sender()))) context.stop(self)

    case AdoptSource(file) =>
      log.info(s"Adopt source ${file.getAbsolutePath}")
      datasetRepo.stagingRepoOpt.map { stagingRepo =>
        future {
          val progressReporter = ProgressReporter(ADOPTING, context.parent)
          progress = Some(progressReporter)
          stagingRepo.acceptFile(file, progressReporter).map { adoptedFile =>
            context.parent ! SourceAdoptionComplete(adoptedFile)
          } getOrElse {
            context.parent ! WorkFailure(s"File not accepted: ${file.getAbsolutePath}")
          }
        } onFailure {
          case t => context.parent ! WorkFailure(t.getMessage, Some(t))
        }
      } getOrElse {
        context.parent ! WorkFailure("Missing staging repository!")
      }

    case GenerateSource =>
      log.info("Generate source")
      datasetRepo.stagingRepoOpt.map { stagingRepo =>
        val sipMapperOpt = datasetRepo.sipMapperOpt
        future {
          val progressReporter = ProgressReporter(GENERATING, context.parent)
          progress = Some(progressReporter)
          val pocketOutput = new FileOutputStream(datasetRepo.pocketFile)
          try {
            val (rawRecordCount, mappedRecordCount) = stagingRepo.generateSource(pocketOutput, datasetRepo.mappedFile, sipMapperOpt, progressReporter)
            val sipFileOpt: Option[File] = datasetRepo.sipRepo.latestSipOpt.map { latestSip =>
              val prefixRepoOpt = latestSip.sipMappingOpt.flatMap(mapping => datasetRepo.orgRepo.sipFactory.prefixRepo(mapping.prefix))
              datasetRepo.sipFiles.foreach(_.delete())
              val sipFile = datasetRepo.createSipFile
              latestSip.copyWithSourceTo(sipFile, datasetRepo.pocketFile, prefixRepoOpt)
              Some(sipFile)
            } getOrElse {
              val generationFactsOpt = db.infoOpt.map(SipGenerationFacts(_))
              generationFactsOpt.map { facts =>
                val sipPrefixRepo = datasetRepo.orgRepo.sipFactory.prefixRepo(facts.prefix)
                sipPrefixRepo.map { prefixRepo =>
                  datasetRepo.sipFiles.foreach(_.delete())
                  val sipFile = datasetRepo.createSipFile
                  prefixRepo.initiateSipZip(sipFile, datasetRepo.pocketFile, facts)
                  Some(sipFile)
                } getOrElse {
                  context.parent ! WorkFailure("Unable to build sip for download")
                  None
                }
              } getOrElse {
                context.parent ! WorkFailure("Unable to create sip generation facts")
                None
              }
            }
            if (rawRecordCount > 0) {
              context.parent ! SourceGenerationComplete(rawRecordCount, mappedRecordCount)
            }
            else {
              sipFileOpt.map(_.delete())
              FileUtils.deleteQuietly(datasetRepo.pocketFile)
              context.parent ! WorkFailure("Zero raw records generated")
            }
          } finally {
            pocketOutput.close()
          }
        } onFailure {
          case t => context.parent ! WorkFailure(t.getMessage, Some(t))
        }
      } getOrElse {
        context.parent ! WorkFailure("No data for generating source")
      }

    case SaveRecords(incrementalOpt) =>
      val stagingFacts = datasetRepo.stagingRepoOpt.map(_.stagingFacts).getOrElse(throw new RuntimeException(s"No staging facts for $datasetRepo"))
      log.info(s"Saving incremental=$incrementalOpt facts=$stagingFacts")
//      val recordDb = datasetRepo.recordDbOpt.getOrElse(throw new RuntimeException(s"Expected record db for $datasetRepo"))
      val sipMapperOpt = datasetRepo.sipMapperOpt

      future {

        incrementalOpt.map { incremental =>
          val parser = PocketParser(stagingFacts)
          val (source, readProgress) = FileHandling.sourceFromFile(incremental.file)
          val progressReporter = ProgressReporter(UPDATING, context.parent)
          progressReporter.setReadProgress(readProgress)
          progress = Some(progressReporter)
//          recordDb.withRecordDb { session =>
//            var recordCount = 0
//            def receiveRecord(rawPocket: Pocket): Unit = {
//              val pocketOpt = sipMapperOpt.map(_.map(rawPocket)).getOrElse(Some(rawPocket))
//              pocketOpt.map { pocket =>
//                log.info(s"Updating ${pocket.id}")
//                recordDb.findRecord(pocket.id, session).map { foundRecord =>
//                  log.info(s"Record found $foundRecord, deleting it")
//                  session.execute(new Delete(foundRecord.path))
//                }
//                // todo: can't do this anymore
////                session.add(pocket.path(datasetRepo.datasetName), pocket.textBytes)
//                recordCount += 1
//              }
//            }
//            try {
//              parser.parse(source, Set.empty[String], receiveRecord, progressReporter)
//              context.parent ! SaveComplete(recordCount)
//            }
//            finally {
//              source.close()
//            }
//          }
        } getOrElse {
          log.info(s"Saving first time")
//          recordDb.createDb()
          var tick = 0
          var time = System.currentTimeMillis()
//          recordDb.withRecordDb { session =>
//            datasetRepo.stagingRepoOpt.map { stagingRepo =>
//              var recordCount = 0
//              def catchPocket(rawPocket: Pocket): Unit = {
//                val pocketOpt = sipMapperOpt.map(_.map(rawPocket)).getOrElse(Some(rawPocket))
//                pocketOpt.map { pocket =>
//                  // todo: can't do this anymore
////                  session.add(pocket.path(datasetRepo.datasetName), pocket.textBytes)
//                  tick += 1
//                  if (tick % 10000 == 0) {
//                    val now = System.currentTimeMillis()
//                    log.info(s"Saving $tick: ${now - time}ms")
//                    session.execute(new Flush())
//                    time = now
//                  }
//                  recordCount += 1
//                }
//              }
//              val progressReporter = ProgressReporter(SAVING, context.parent)
//              progress = Some(progressReporter)
//              stagingRepo.parsePockets(catchPocket, progressReporter)
//              if (progress.isDefined) {
//                log.info(s"Saved, optimizing..")
//                recordDb.withRecordDb(_.execute(new Optimize()))
//                context.parent ! SaveComplete(recordCount)
//              }
//              else {
//                context.parent ! WorkFailure("Interrupted while saving")
//              }
//            }
//          }
        }
      } onFailure {
        case t => context.parent ! WorkFailure(t.getMessage, Some(t))
      }
  }

}

