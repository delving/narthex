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

package dataset

import java.io.File

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import analysis.Analyzer
import analysis.Analyzer.{AnalysisComplete, AnalyzeFile}
import dataset.DatasetActor._
import dataset.DsInfo.DsState._
import dataset.SourceRepo.SourceFacts
import harvest.Harvester
import harvest.Harvester.{HarvestAdLib, HarvestComplete, HarvestPMH}
import harvest.Harvesting.HarvestType._
import mapping.CategoryCounter
import mapping.CategoryCounter.{CategoryCountComplete, CountCategories}
import org.OrgActor.DatasetQuestion
import org.OrgContext
import org.apache.commons.io.FileUtils._
import org.joda.time.DateTime
import play.api.Logger
import record.SourceProcessor
import record.SourceProcessor._
import services.ProgressReporter.ProgressState._
import services.ProgressReporter.ProgressType._
import services.ProgressReporter.{ProgressState, ProgressType}
import triplestore.GraphProperties._
import triplestore.GraphSaver
import triplestore.GraphSaver.{GraphSaveComplete, SaveGraphs}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

object DatasetActor {

  // state machine

  sealed trait DatasetActorState

  case object Idle extends DatasetActorState

  case object Harvesting extends DatasetActorState

  case object Adopting extends DatasetActorState

  case object Analyzing extends DatasetActorState

  case object Generating extends DatasetActorState

  case object Processing extends DatasetActorState

  case object Saving extends DatasetActorState

  case object Categorizing extends DatasetActorState

  trait DatasetActorData

  case object Dormant extends DatasetActorData

  case class Active(childOpt: Option[ActorRef], progressState: ProgressState, progressType: ProgressType = TYPE_IDLE, count: Int = 0) extends DatasetActorData

  case class InError(error: String) extends DatasetActorData


  // messages to receive

  case class Command(name: String)

  case class StartHarvest(modifiedAfter: Option[DateTime], justDate: Boolean)

  case object StartAnalysis

  case class Incremental(modifiedAfter: DateTime, file: File)

  case class StartProcessing(incrementalOpt: Option[Incremental])

  case class StartSaving(incrementalOpt: Option[Incremental])

  case object StartCategoryCounting

  case object InterruptWork

  case class WorkFailure(message: String, exceptionOpt: Option[Throwable] = None)

  case object CheckState

  case class ProgressTick(progressState: ProgressState, progressType: ProgressType = TYPE_IDLE, count: Int = 0)

  // create one

  def props(datasetContext: DatasetContext) = Props(new DatasetActor(datasetContext))

}

class DatasetActor(val datasetContext: DatasetContext) extends FSM[DatasetActorState, DatasetActorData] with ActorLogging {

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
    case _: Exception => Stop
  }

  val dsInfo = datasetContext.dsInfo

  val errorMessage = dsInfo.getLiteralProp(datasetErrorMessage).getOrElse("")

  startWith(Idle, if (errorMessage.nonEmpty) InError(errorMessage) else Dormant)

  def sendBusy() = self ! ProgressTick(PREPARING, BUSY, 100)

  when(Idle) {

    case Event(StartHarvest(modifiedAfter, justDate), Dormant) =>
      sendBusy()
      datasetContext.dropTree()

      log.info(s"Start harvest event")

      def prop(p: NXProp) = dsInfo.getLiteralProp(p).getOrElse("")

      harvestTypeFromString(prop(harvestType)).map { harvestType =>
        val (url, ds, pre, se) = (prop(harvestURL), prop(harvestDataset), prop(harvestPrefix), prop(harvestSearch))
        val kickoff = harvestType match {
          case PMH => HarvestPMH(url, ds, pre, modifiedAfter, justDate)
          case PMH_REC => HarvestPMH(url, ds, pre, modifiedAfter, justDate)
          case ADLIB => HarvestAdLib(url, ds, se, modifiedAfter)
        }
        val harvester = context.actorOf(Harvester.props(datasetContext), "harvester")
        harvester ! kickoff
        goto(Harvesting) using Active(Some(harvester), HARVESTING)
      } getOrElse {
        stay() using InError("Unable to determine harvest type")
      }

    case Event(AdoptSource(file), Dormant) =>
      sendBusy()
      val sourceProcessor = context.actorOf(SourceProcessor.props(datasetContext), "source-adopter")
      sourceProcessor ! AdoptSource(file)
      goto(Adopting) using Active(Some(sourceProcessor), ADOPTING)

    case Event(GenerateSipZip, Dormant) =>
      sendBusy()
      val sourceProcessor = context.actorOf(SourceProcessor.props(datasetContext), "source-generator")
      sourceProcessor ! GenerateSipZip
      goto(Generating) using Active(Some(sourceProcessor), GENERATING)

    case Event(StartAnalysis, Dormant) =>
      sendBusy()
      // todo: are we analyzing raw stuff or delimited stuff?
      log.info("Start analysis")
      if (datasetContext.processedRepo.nonEmpty) {
        // todo: kill all when finished so this can not lookup, just create
        val analyzer = context.child("analyzer").getOrElse(context.actorOf(Analyzer.props(datasetContext), "analyzer"))
        analyzer ! AnalyzeFile(datasetContext.processedRepo.baseFile)
        goto(Analyzing) using Active(Some(analyzer), SPLITTING)
      } else datasetContext.rawFile.map { rawFile =>
        val analyzer = context.child("analyzer").getOrElse(context.actorOf(Analyzer.props(datasetContext), "analyzer"))
        analyzer ! AnalyzeFile(rawFile)
        goto(Analyzing) using Active(Some(analyzer), SPLITTING)
      } getOrElse {
        // todo: sure about this?
        self ! GenerateSipZip
        stay()
      }

    case Event(StartProcessing(incrementalOpt), Dormant) =>
      sendBusy()
      val sourceProcessor = context.actorOf(SourceProcessor.props(datasetContext), "source-processor")
      sourceProcessor ! Process(incrementalOpt)
      goto(Processing) using Active(Some(sourceProcessor), PROCESSING)

    case Event(StartSaving(incrementalOpt), Dormant) =>
      sendBusy()
      val graphSaver = context.actorOf(GraphSaver.props(datasetContext.processedRepo, OrgContext.ts), "graph-saver")
      graphSaver ! SaveGraphs(incrementalOpt)
      goto(Saving) using Active(Some(graphSaver), PROCESSING)

    case Event(StartCategoryCounting, Dormant) =>
      sendBusy()
      if (datasetContext.processedRepo.nonEmpty) {
        val categoryCounter = context.actorOf(CategoryCounter.props(datasetContext), "category-counter")
        categoryCounter ! CountCategories()
        goto(Categorizing) using Active(Some(categoryCounter), CATEGORIZING)
      }
      else {
        stay() using InError(s"No source file for categorizing $datasetContext")
      }

    case Event(DatasetQuestion(listener, Command(commandName)), Dormant) =>
      val reply = Try {
        commandName match {
          case "delete" =>
            Await.ready(datasetContext.dsInfo.dropDataset, 30.seconds)
            deleteQuietly(datasetContext.rootDir)
            "deleted"

          case "remove source" =>
            datasetContext.dropSourceRepo()
            "source removed"

          case "remove processed" =>
            datasetContext.dropProcessedRepo()
            "processed data removed"

          case "remove tree" =>
            datasetContext.dropTree()
            "tree removed"

          case "start first harvest" =>
            val typeInfo = dsInfo.getLiteralProp(harvestType)
            val harvestTypeOpt = typeInfo.flatMap(harvestTypeFromString)
            harvestTypeOpt.map { harvestType =>
              log.info(s"Starting first harvest with type $harvestType")
              datasetContext.createSourceRepo(SourceFacts(harvestType))
              self ! StartHarvest(None, justDate = true)
              "harvest started"
            } getOrElse {
              val message = s"Unable to harvest $datasetContext: unknown harvest type [$typeInfo]"
              log.info(s"DSINFO:\n${dsInfo.toTurtle}")
              self ! WorkFailure(message, None)
              message
            }

          case "start processing" =>
            self ! StartProcessing(None)
            "processing started"

          case "start analysis" =>
            datasetContext.dropTree()
            self ! StartAnalysis
            "analysis started"

          case "start saving" =>
            // full save, not incremental
            self ! StartSaving(None)
            "saving started"

          // todo:
          //def startCategoryCounts() = OrgActor.actor ! dsInfo.createMessage(StartCategoryCounting)

          case _ =>
            log.warning(s"$this sent unrecognized command $commandName")
            "unrecognized"
        }
      }
      val replyString: String = reply.getOrElse(s"oops: exception")
      listener ! replyString
      stay()
  }

  when(Harvesting) {

    case Event(HarvestComplete(incrementalOpt), active: Active) =>
      incrementalOpt.map { incremental =>
        incremental.fileOpt.map { newFile =>
          dsInfo.setState(SOURCED)
          dsInfo.setHarvestCron(dsInfo.harvestCron)
          self ! StartProcessing(Some(Incremental(incremental.modifiedAfter, newFile)))
        }
      } getOrElse {
        self ! GenerateSipZip
      }
      active.childOpt.map(_ ! PoisonPill)
      goto(Idle) using Dormant

  }

  when(Adopting) {

    case Event(SourceAdoptionComplete(file), active: Active) =>
      if (datasetContext.sipRepo.latestSipOpt.isDefined) dsInfo.setState(PROCESSABLE)
      datasetContext.dropTree()
      active.childOpt.map(_ ! PoisonPill)
      self ! GenerateSipZip
      goto(Idle) using Dormant

  }

  when(Generating) {

    case Event(SipZipGenerationComplete(recordCount), active: Active) =>
      log.info(s"Generated $recordCount pockets")
      dsInfo.setState(MAPPABLE)
      dsInfo.setRecordCount(recordCount)
      // todo: figure this out
      //        val rawFile = datasetContext.createRawFile(datasetContext.pocketFile.getName)
      //        FileUtils.copyFile(datasetContext.pocketFile, rawFile)
      //        db.setStatus(RAW_POCKETS)
      active.childOpt.map(_ ! PoisonPill)
      goto(Idle) using Dormant

  }

  when(Analyzing) {

    case Event(AnalysisComplete(errorOption), active: Active) =>
      if (errorOption.isDefined)
        datasetContext.dropTree()
      else
        dsInfo.setState(ANALYZED)
      active.childOpt.map(_ ! PoisonPill)
      goto(Idle) using Dormant

  }

  when(Processing) {

    case Event(ProcessingComplete(validRecords, invalidRecords), active: Active) =>
      dsInfo.setState(PROCESSED)
      dsInfo.setProcessedRecordCounts(validRecords, invalidRecords)
      active.childOpt.map(_ ! PoisonPill)
      goto(Idle) using Dormant

  }

  when(Saving) {

    case Event(GraphSaveComplete, active: Active) =>
      dsInfo.setState(SAVED)
      active.childOpt.map(_ ! PoisonPill)
      goto(Idle) using Dormant

  }

  when(Categorizing) {

    case Event(CategoryCountComplete(dataset, categoryCounts), active: Active) =>
      context.parent ! CategoryCountComplete(datasetContext.dsInfo.spec, categoryCounts)
      active.childOpt.map(_ ! PoisonPill)
      goto(Idle) using Dormant

  }

  whenUnhandled {

    case Event(tick: ProgressTick, active: Active) =>
      stay() using active.copy(progressState = tick.progressState, progressType = tick.progressType, count = tick.count)

    case Event(DatasetQuestion(listener, Command(commandName)), InError(message)) =>
      Logger.info(s"In error. Command name: $commandName")
      if (commandName == "clear error") {
        dsInfo.removeLiteralProp(datasetErrorMessage).map { m =>
          listener ! "error cleared"
        }
        goto(Idle) using Dormant
      }
      else {
        listener ! message
        stay()
      }

    case Event(InterruptWork, active: Active) =>
      log.info(s"Sending interrupt while in $stateName/$active)")
      active.childOpt.map { child =>
        log.info(s"Interrupting $child")
        child ! InterruptWork
      }
      stay()

    case Event(WorkFailure(message, exceptionOpt), active: Active) =>
      log.warning(s"Work failure [$message] while in [$active]")
      dsInfo.setError(message).map(ok => log.info(s"Set error to [$message]"))
      exceptionOpt.map(ex => log.error(ex, message)).getOrElse(log.error(message))
      active.childOpt.map(_ ! PoisonPill)
      goto(Idle) using InError(message)

    case Event(WorkFailure(message, exceptionOpt), _) =>
      log.warning(s"Work failure $message while dormant")
      exceptionOpt.map(log.warning(message, _))
      dsInfo.setError(message)
      exceptionOpt.map(ex => log.error(ex, message)).getOrElse(log.error(message))
      goto(Idle) using InError(message)

    case Event(DatasetQuestion(listener, CheckState), data) =>
      listener ! data
      stay()

    case Event(request, data) =>
      log.warning(s"Unhandled request $request in state $stateName/$data")
      stay()
  }

}









