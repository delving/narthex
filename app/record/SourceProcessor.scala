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
import record.PocketParser.Pocket
import record.SourceProcessor._
import services.FileHandling._
import services.{FileHandling, ProgressReporter}

import scala.concurrent._
import scala.language.postfixOps

object SourceProcessor {

  case class AdoptSource(file: File)

  case class SourceAdoptionComplete(file: File)

  case object GenerateSipZip

  case class SipZipGenerationComplete(recordCount: Int)

  case class Process(incrementalOpt: Option[IncrementalSave])

  case class ProcessingComplete(validRecords: Int, invalidRecords: Int)

  def props(datasetRepo: DatasetRepo) = Props(new SourceProcessor(datasetRepo))

}

class SourceProcessor(val datasetRepo: DatasetRepo) extends Actor with ActorLogging {

  import context.dispatcher

  var progress: Option[ProgressReporter] = None
  val db = datasetRepo.datasetDb

  def receive = {

    case InterruptWork =>
      if (!progress.exists(_.interruptBy(sender()))) context.stop(self)

    case AdoptSource(file) =>
      log.info(s"Adopt source ${file.getAbsolutePath}")
      datasetRepo.sourceRepoOpt.map { sourceRepo =>
        future {
          val progressReporter = ProgressReporter(ADOPTING, context.parent)
          progress = Some(progressReporter)
          sourceRepo.acceptFile(file, progressReporter).map { adoptedFile =>
            context.parent ! SourceAdoptionComplete(adoptedFile)
          } getOrElse {
            context.parent ! WorkFailure(s"File not accepted: ${file.getAbsolutePath}")
          }
        } onFailure {
          case t => context.parent ! WorkFailure(t.getMessage, Some(t))
        }
      } getOrElse {
        context.parent ! WorkFailure("Missing source repository!")
      }

    case GenerateSipZip =>
      log.info("Generate SipZip")
      datasetRepo.sourceRepoOpt.map { sourceRepo =>
        future {
          val progressReporter = ProgressReporter(GENERATING, context.parent)
          progress = Some(progressReporter)
          val pocketOutput = new FileOutputStream(datasetRepo.pocketFile)
          try {
            val pocketCount = sourceRepo.generatePockets(pocketOutput, progressReporter)
            val sipFileOpt: Option[File] = datasetRepo.sipRepo.latestSipOpt.map { latestSip =>
              val prefixRepoOpt = latestSip.sipMappingOpt.flatMap(mapping => datasetRepo.orgRepo.sipFactory.prefixRepo(mapping.prefix))
              datasetRepo.sipFiles.foreach(_.delete())
              val sipFile = datasetRepo.createSipFile
              // todo: pocketFile is not yet closed!
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
            if (pocketCount > 0) {
              context.parent ! SipZipGenerationComplete(pocketCount)
            }
            else {
              sipFileOpt.map(_.delete())
              FileUtils.deleteQuietly(datasetRepo.pocketFile)
              context.parent ! WorkFailure("Zero pockets generated")
            }
          } finally {
            pocketOutput.close()
          }
        } onFailure {
          case t => context.parent ! WorkFailure(t.getMessage, Some(t))
        }
      } getOrElse {
        context.parent ! WorkFailure("No data for generating SipZip")
      }

    case Process(incrementalOpt) =>
      val sourceFacts = datasetRepo.sourceRepoOpt.map(_.sourceFacts).getOrElse(throw new RuntimeException(s"No source facts for $datasetRepo"))
      val sipMapper = datasetRepo.sipMapperOpt.getOrElse(throw new RuntimeException(s"No sip mapper for $datasetRepo"))
      if (incrementalOpt.isEmpty) datasetRepo.processedRepo.clear()

      val work = future {

        var sourceFile = datasetRepo.processedRepo.createFile
        val sourceOutput = writer(sourceFile)
        var validRecords = 0
        var invalidRecords = 0
        var time = System.currentTimeMillis()

        def catchPocket(rawPocket: Pocket): Unit = {
          val pocketOpt = sipMapper.map(rawPocket)
          pocketOpt.map { pocket =>
            sourceOutput.write(pocket.text)
            validRecords += 1
          } getOrElse {
            invalidRecords += 1
          }
          val total = validRecords + invalidRecords
          if (total % 10000 == 0) {
            val now = System.currentTimeMillis()
            log.info(s"Processing $total: ${now - time}ms")
            time = now
          }
        }

        incrementalOpt.map { incremental =>
          log.info(s"Processing incremental $sourceFacts")
          val (source, readProgress) = FileHandling.sourceFromFile(incremental.file)
          try {
            val progressReporter = ProgressReporter(PROCESSING, context.parent)
            progressReporter.setReadProgress(readProgress)
            progress = Some(progressReporter)
            val parser = PocketParser(sourceFacts)
            parser.parse(source, Set.empty[String], catchPocket, progressReporter)
          }
          finally {
            source.close()
          }
        } getOrElse {
          log.info(s"Processing all $sourceFacts")
          datasetRepo.sourceRepoOpt.map { sourceRepo =>
            val progressReporter = ProgressReporter(PROCESSING, context.parent)
            progress = Some(progressReporter)
            sourceRepo.parsePockets(catchPocket, progressReporter)
          }
        }

        sourceOutput.close()
        context.parent ! ProcessingComplete(validRecords, invalidRecords)
      }

      work.onFailure {
        case t => context.parent ! WorkFailure(t.getMessage, Some(t))
      }
  }

}

