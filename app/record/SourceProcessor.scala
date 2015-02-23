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
import dataset.DatasetActor.{Incremental, InterruptWork, WorkFailure}
import dataset.DatasetContext
import dataset.SipFactory.SipGenerationFacts
import dataset.SipRepo.URIErrorsException
import eu.delving.groovy.DiscardRecordException
import org.apache.commons.io.FileUtils.deleteQuietly
import org.xml.sax.SAXException
import record.PocketParser.Pocket
import record.SourceProcessor._
import services.FileHandling._
import services.ProgressReporter.ProgressState._
import services.{FileHandling, ProgressReporter}

import scala.concurrent._
import scala.language.postfixOps
import scala.util.{Failure, Success}

object SourceProcessor {

  case class AdoptSource(file: File)

  case class SourceAdoptionComplete(file: File)

  case object GenerateSipZip

  case class SipZipGenerationComplete(recordCount: Int)

  case class Process(incrementalOpt: Option[Incremental])

  case class ProcessingComplete(validRecords: Int, invalidRecords: Int, incrementalOpt: Option[Incremental])

  def props(datasetContext: DatasetContext) = Props(new SourceProcessor(datasetContext))

}

class SourceProcessor(val datasetContext: DatasetContext) extends Actor with ActorLogging {

  import context.dispatcher

  var progress: Option[ProgressReporter] = None
  val dsInfo = datasetContext.dsInfo

  def receive = {

    case InterruptWork =>
      progress.map(_.interruptBy(sender()))

    case AdoptSource(file) =>
      log.info(s"Adopt source ${file.getAbsolutePath}")
      datasetContext.sourceRepoOpt.map { sourceRepo =>
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
      datasetContext.sourceRepoOpt.map { sourceRepo =>
        future {
          val progressReporter = ProgressReporter(GENERATING, context.parent)
          progress = Some(progressReporter)
          val pocketOutput = new FileOutputStream(datasetContext.pocketFile)
          val pocketCount = sourceRepo.generatePockets(pocketOutput, progressReporter)
          val sipFileOpt: Option[File] = datasetContext.sipRepo.latestSipOpt.map { latestSip =>
            val prefixRepoOpt = latestSip.sipMappingOpt.flatMap(mapping => datasetContext.orgContext.sipFactory.prefixRepo(mapping.prefix))
            datasetContext.sipFiles.foreach(_.delete())
            val sipFile = datasetContext.createSipFile
            pocketOutput.close()
            latestSip.copyWithSourceTo(sipFile, datasetContext.pocketFile, prefixRepoOpt)
            Some(sipFile)
          } getOrElse {
            val facts = SipGenerationFacts(dsInfo)
            val sipPrefixRepo = datasetContext.orgContext.sipFactory.prefixRepo(facts.prefix)
            sipPrefixRepo.map { prefixRepo =>
              datasetContext.sipFiles.foreach(_.delete())
              val sipFile = datasetContext.createSipFile
              pocketOutput.close()
              prefixRepo.initiateSipZip(sipFile, datasetContext.pocketFile, facts)
              Some(sipFile)
            } getOrElse {
              pocketOutput.close()
              context.parent ! WorkFailure("Unable to build sip for download")
              None
            }
          }
          if (pocketCount > 0) {
            context.parent ! SipZipGenerationComplete(pocketCount)
          }
          else {
            sipFileOpt.map(_.delete())
            deleteQuietly(datasetContext.pocketFile)
            context.parent ! WorkFailure("Zero pockets generated")
          }
        } onFailure {
          case t => context.parent ! WorkFailure(t.getMessage, Some(t))
        }
      } getOrElse {
        context.parent ! WorkFailure("No data for generating SipZip")
      }

    case Process(incrementalOpt) =>
      val sourceFacts = datasetContext.sourceRepoOpt.map(_.sourceFacts).getOrElse {
        // todo: an exception should be interpreted by the parent as a WorkFailure!
        val message = s"No source facts for $datasetContext"
        context.parent ! WorkFailure(message)
        throw new RuntimeException(message)
      }
      val sipMapper = datasetContext.sipMapperOpt.getOrElse {
        // todo: an exception should be interpreted by the parent as a WorkFailure!
        val message = s"No sip mapper for $datasetContext"
        context.parent ! WorkFailure(message)
        throw new RuntimeException(message)
      }
      if (incrementalOpt.isEmpty) datasetContext.processedRepo.clear()

      val work = future {

        val processedOutput = datasetContext.processedRepo.createOutput
        val xmlOutput = writer(processedOutput.xmlFile)
        val errorOutput = writer(processedOutput.errorFile)
        var validRecords = 0
        var invalidRecords = 0
        var time = System.currentTimeMillis()

        def writeError(heading: String, error: String) = {
          invalidRecords += 1
          errorOutput.write(
            s"""
               |===== $invalidRecords: $heading =====
               |$error
             """.stripMargin.trim
          )
          errorOutput.write("\n")
        }

        def catchPocket(rawPocket: Pocket): Unit = {
          val pocketTry = sipMapper.executeMapping(rawPocket)
          pocketTry match {
            case Success(pocket) =>
              pocket.writeTo(xmlOutput)
              validRecords += 1

            case Failure(ue: URIErrorsException) =>
              writeError("URI ERRORS", ue.uriErrors.mkString("\n"))

            case Failure(disc: DiscardRecordException) =>
              writeError("DISCARDED RECORD", disc.getMessage)

            case Failure(sax: SAXException) =>
              writeError("XSD ERROR", sax.getMessage)

            case Failure(unexpected: Throwable) =>
              writeError("UNEXPECTED ERROR", unexpected.toString)
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
            val parser = new PocketParser(sourceFacts)
            parser.parse(source, Set.empty[String], catchPocket, progressReporter)
          }
          finally {
            source.close()
          }
        } getOrElse {
          log.info(s"Processing all $sourceFacts")
          datasetContext.sourceRepoOpt.map { sourceRepo =>
            val progressReporter = ProgressReporter(PROCESSING, context.parent)
            progress = Some(progressReporter)
            sourceRepo.parsePockets(catchPocket, progressReporter)
          }
        }

        xmlOutput.close()
        errorOutput.close()
        if (invalidRecords == 0) deleteQuietly(processedOutput.errorFile)
        context.parent ! ProcessingComplete(validRecords, invalidRecords, incrementalOpt)
      }

      work.onFailure {
        case t => context.parent ! WorkFailure(t.getMessage, Some(t))
      }
  }

}

