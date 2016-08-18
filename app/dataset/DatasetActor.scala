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
import dataset.DsInfo._
import dataset.SourceRepo.SourceFacts
import harvest.Harvester
import harvest.Harvester.{HarvestAdLib, HarvestComplete, HarvestPMH}
import harvest.Harvesting.HarvestType._
import mapping.CategoryCounter.{CategoryCountComplete, CountCategories}
import mapping.Skosifier.SkosificationComplete
import mapping.{CategoryCounter, Skosifier}
import org.OrgContext
import org.apache.commons.io.FileUtils._
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{Json, Writes}
import record.SourceProcessor
import record.SourceProcessor._
import services.MailService
import services.ProgressReporter
import services.ProgressReporter.ProgressState._
import services.ProgressReporter.ProgressType._
import services.ProgressReporter.{ProgressState, ProgressType}
import triplestore.GraphProperties._
import triplestore.{GraphSaver}
import triplestore.GraphSaver.{GraphSaveComplete, SaveGraphs}
import triplestore.Sparql.SkosifiedField

import scala.concurrent.Await
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

  case object Skosifying extends DatasetActorState

  case object Categorizing extends DatasetActorState

  trait DatasetActorData

  case object Dormant extends DatasetActorData

  case class Active(spec: String, childOpt: Option[ActorRef], 
                    progressState: ProgressState, progressType: ProgressType = TYPE_IDLE, count: Int = 0,
                    interrupt: Boolean = false) extends DatasetActorData

  implicit val activeWrites = new Writes[Active] {
    def writes(active: Active) = Json.obj(
      "datasetSpec" -> active.spec,
      "progressState" -> active.progressState.toString,
      "progressType" -> active.progressType.toString,
      "count" -> active.count
    )
  }

  case class InError(error: String) extends DatasetActorData


  // messages to receive

  trait HarvestStrategy

  case class ModifiedAfter(mod: DateTime, justDate: Boolean) extends HarvestStrategy

  case object Sample extends HarvestStrategy

  case object FromScratch extends HarvestStrategy

  case object FromScratchIncremental extends HarvestStrategy

  case class StartHarvest(strategy: HarvestStrategy)

  case class Command(name: String)

  case class StartAnalysis(processed: Boolean)

  case class Incremental(modifiedAfter: Option[DateTime], file: File)

  case class StartProcessing(incrementalOpt: Option[Incremental])

  case class StartSaving(incrementalOpt: Option[Incremental])

  case class StartSkosification(skosifiedField: SkosifiedField)

  case object StartCategoryCounting

  case class WorkFailure(message: String, exceptionOpt: Option[Throwable] = None)

  case class ProgressTick(reporterOpt: Option[ProgressReporter], progressState: ProgressState, progressType: ProgressType = TYPE_IDLE, count: Int = 0)

  // create one

  def props(datasetContext: DatasetContext, mailService: MailService, orgContext: OrgContext) = Props(new DatasetActor(datasetContext, mailService, orgContext))

}

class DatasetActor(val datasetContext: DatasetContext, mailService: MailService, orgContext: OrgContext) extends FSM[DatasetActorState, DatasetActorData] with ActorLogging {

  import context.dispatcher


  override val supervisorStrategy = OneForOneStrategy() {
    case throwable: Throwable =>
      self ! WorkFailure(s"Child failure: $throwable", Some(throwable))
      Stop
  }

  val dsInfo = datasetContext.dsInfo

  val errorMessage = dsInfo.getLiteralProp(datasetErrorMessage).getOrElse("")

  def broadcastRaw(message: String) = context.system.actorSelection("/system/websockets/*") ! message

  def broadcastIdleState() = broadcastRaw(Json.stringify(Json.toJson(datasetContext.dsInfo)))

  def broadcastProgress(active: Active) = broadcastRaw(Json.stringify(Json.toJson(active)))

  startWith(Idle, if (errorMessage.nonEmpty) InError(errorMessage) else Dormant)

  when(Idle) {

    case Event(Command(commandName), Dormant) =>
      val replyTry = Try {

        def startHarvest(strategy: HarvestStrategy) = {
          val harvestTypeStringOpt = dsInfo.getLiteralProp(harvestType)
          log.info(s"Start harvest $strategy, type is $harvestTypeStringOpt")
          val harvestTypeOpt = harvestTypeStringOpt.flatMap(harvestTypeFromString)
          harvestTypeOpt.map { harvestType =>
            log.info(s"Starting harvest $strategy with type $harvestType")
            strategy match {
              case FromScratch =>
                datasetContext.sourceRepoOpt match {
                  case Some(sourceRepo) =>
                    sourceRepo.clearData()
                  case None =>
                    // no source repo, use the default for the harvest type
                    datasetContext.createSourceRepo(SourceFacts(harvestType))
                }
              case FromScratchIncremental =>
                datasetContext.sourceRepoOpt match {
                  case Some(sourceRepo) =>
                    sourceRepo.clearData()
                  case None =>
                    // no source repo, use the default for the harvest type
                    datasetContext.createSourceRepo(SourceFacts(harvestType))
                }
              case _ =>
            }
            self ! StartHarvest(strategy)
            "harvest started"
          } getOrElse {
            val message = s"Unable to harvest $datasetContext: unknown harvest type [$harvestTypeStringOpt]"
            self ! WorkFailure(message, None)
            message
          }
        }

        commandName match {
          case "delete" =>
            Await.ready(datasetContext.dsInfo.dropDataset, 2.minutes)
            deleteQuietly(datasetContext.rootDir)
            datasetContext.sipFiles.foreach(_.delete())
            self ! PoisonPill
            "deleted"

          case "delete records" =>
            Await.ready(datasetContext.dropRecords, 2.minutes)
            broadcastIdleState()
            "deleted records"

          case "delete index" =>
            Await.ready(datasetContext.dropIndex, 2.minutes)
            broadcastIdleState()
            "deleted index"

          case "remove raw" =>
            datasetContext.dropRaw()
            broadcastIdleState()
            "raw removed"

          case "remove source" =>
            datasetContext.dropSourceRepo()
            broadcastIdleState()
            "source removed"

          case "remove processed" =>
            datasetContext.dropProcessedRepo()
            broadcastIdleState()
            "processed data removed"

          case "remove tree" =>
            datasetContext.dropTree()
            broadcastIdleState()
            "tree removed"

          case "start sample harvest" =>
            startHarvest(Sample)

          case "start first harvest" =>
            startHarvest(FromScratch)

          case "start generating sip" =>
            self ! GenerateSipZip
            "sip generation started"

          case "start processing" =>
            self ! StartProcessing(None)
            "processing started"

          case "start raw analysis" =>
            datasetContext.dropTree()
            self ! StartAnalysis(processed = false)
            "analysis started"

          case "start processed analysis" =>
            datasetContext.dropTree()
            self ! StartAnalysis(processed = true)
            "analysis started"

          case "start saving" =>
            // full save, not incremental
            self ! StartSaving(None)
            "saving started"

          case "start skosification" =>
            self ! StartSkosification
            "skosification started"

          case "refresh" =>
            log.info("refresh")
            broadcastIdleState()
            "refreshed"

          // todo: category counting?

          case _ =>
            log.warning(s"$this sent unrecognized command $commandName")
            "unrecognized"
        }
      } recover {
        case e: Exception =>
          log.error(e, "Command exception")
          s"exception: $e"
      }
      val replyString: String = replyTry.getOrElse(s"unrecovered exception")
      log.info(s"Command $commandName: $replyString")
      stay()

    case Event(StartHarvest(strategy), Dormant) =>
      datasetContext.dropTree()
      def prop(p: NXProp) = dsInfo.getLiteralProp(p).getOrElse("")
      harvestTypeFromString(prop(harvestType)).map { harvestType =>
        val (url, ds, pre, se) = (prop(harvestURL), prop(harvestDataset), prop(harvestPrefix), prop(harvestSearch))
        val kickoff = harvestType match {
          case PMH => HarvestPMH(strategy, url, ds, pre)
          case ADLIB => HarvestAdLib(strategy, url, ds, se)
        }
        val harvester = context.actorOf(Harvester.props(datasetContext, orgContext.HARVEST_TIMEOUT), "harvester")
        harvester ! kickoff
        goto(Harvesting) using Active(dsInfo.spec, Some(harvester), HARVESTING)
      } getOrElse {
        stay() using InError("Unable to determine harvest type")
      }

    case Event(AdoptSource(file), Dormant) =>
      val sourceProcessor = context.actorOf(SourceProcessor.props(datasetContext, orgContext), "source-adopter")
      sourceProcessor ! AdoptSource(file)
      goto(Adopting) using Active(dsInfo.spec, Some(sourceProcessor), ADOPTING)

    case Event(GenerateSipZip, Dormant) =>
      val sourceProcessor = context.actorOf(SourceProcessor.props(datasetContext, orgContext), "source-generator")
      sourceProcessor ! GenerateSipZip
      goto(Generating) using Active(dsInfo.spec, Some(sourceProcessor), GENERATING)

    case Event(StartAnalysis(processed), Dormant) =>
      log.info(s"Start analysis processed=$processed")
      if (processed) {
        val analyzer = context.actorOf(Analyzer.props(datasetContext), "analyzer-processed")
        analyzer ! AnalyzeFile(datasetContext.processedRepo.baseOutput.xmlFile, processed)
        goto(Analyzing) using Active(dsInfo.spec, Some(analyzer), SPLITTING)
      }
      else {
        val rawFile = datasetContext.rawXmlFile.getOrElse(throw new Exception(s"Unable to find 'raw' file to analyze"))
        val analyzer = context.actorOf(Analyzer.props(datasetContext), "analyzer-raw")
        analyzer ! AnalyzeFile(rawFile, processed = false)
        goto(Analyzing) using Active(dsInfo.spec, Some(analyzer), SPLITTING)
      }

    case Event(StartProcessing(incrementalOpt), Dormant) =>
      val sourceProcessor = context.actorOf(SourceProcessor.props(datasetContext, orgContext), "source-processor")
      sourceProcessor ! Process(incrementalOpt)
      goto(Processing) using Active(dsInfo.spec, Some(sourceProcessor), PROCESSING)

    case Event(StartSaving(incrementalOpt), Dormant) =>
      val graphSaver = context.actorOf(GraphSaver.props(datasetContext, orgContext), "graph-saver")
      graphSaver ! SaveGraphs(incrementalOpt)
      goto(Saving) using Active(dsInfo.spec, Some(graphSaver), PROCESSING)

    case Event(StartSkosification(skosifiedField), Dormant) =>
      val skosifier = context.actorOf(Skosifier.props(dsInfo, orgContext), "skosifier")
      skosifier ! skosifiedField
      goto(Skosifying) using Active(dsInfo.spec, Some(skosifier), SKOSIFYING)

    case Event(StartCategoryCounting, Dormant) =>
      if (datasetContext.processedRepo.nonEmpty) {
        implicit val ts = orgContext.ts
        val categoryCounter = context.actorOf(CategoryCounter.props(dsInfo, datasetContext.processedRepo, orgContext), "category-counter")
        categoryCounter ! CountCategories
        goto(Categorizing) using Active(dsInfo.spec, Some(categoryCounter), CATEGORIZING)
      }
      else {
        stay() using InError(s"No source file for categorizing $datasetContext")
      }

  }

  when(Harvesting) {

    case Event(HarvestComplete(strategy, fileOpt, noRecordsMatch), active: Active) =>
      def processIncremental(fileOpt: Option[File], noRecordsMatch: Boolean=false, mod: Option[DateTime]) = {
        noRecordsMatch match {
          case true =>
            Logger.info("NoRecordsMatch, so setting state to Incremental Saved")
            dsInfo.setState(INCREMENTAL_SAVED)
            if (dsInfo.getLiteralProp(harvestIncrementalMode).getOrElse("false") != "true") {
              dsInfo.setHarvestIncrementalMode(true)
            }
          case _ =>
            dsInfo.removeState(SAVED)
            dsInfo.removeState(ANALYZED)
            dsInfo.removeState(INCREMENTAL_SAVED)
            dsInfo.removeState(PROCESSED)
            dsInfo.removeState(PROCESSABLE)
            dsInfo.setState(SOURCED)
            dsInfo.setLastHarvestTime(incremental = true)
        }
        dsInfo.setHarvestCron(dsInfo.currentHarvestCron)
        dsInfo.updatedSpecCountFromFile(dsInfo.spec, orgContext.narthexDataDir, orgContext.orgId)
        fileOpt match {
          case Some(file) =>
            if (datasetContext.sipMapperOpt.isDefined) {
              log.info(s"There is a mapper, so trigger processing")
              self ! StartProcessing(Some(Incremental(mod, file)))
            }
            else {
              log.info("No mapper, so generating sip zip only")
              self ! GenerateSipZip
            }
          case None =>
            log.info("No incremental file, back to sleep")
        }
      }
      strategy match {
        case Sample =>
          dsInfo.setState(RAW)
        case FromScratch =>
          dsInfo.removeState(SAVED)
          dsInfo.removeState(ANALYZED)
          dsInfo.removeState(INCREMENTAL_SAVED)
          dsInfo.removeState(PROCESSED)
          dsInfo.setState(SOURCED)
          dsInfo.setLastHarvestTime(incremental = false)


        case ModifiedAfter(mod, _) =>
          processIncremental(fileOpt, noRecordsMatch, Some(mod))

        case FromScratchIncremental =>
          processIncremental(fileOpt, noRecordsMatch, None)
      }
      active.childOpt.foreach(_ ! PoisonPill)
      goto(Idle) using Dormant
  }

  when(Adopting) {

    case Event(SourceAdoptionComplete(file), active: Active) =>
      dsInfo.setState(SOURCED)
      if (datasetContext.sipRepo.latestSipOpt.isDefined) dsInfo.setState(PROCESSABLE)
      datasetContext.dropTree()
      active.childOpt.foreach(_ ! PoisonPill)
      self ! GenerateSipZip
      goto(Idle) using Dormant

  }

  when(Generating) {

    case Event(SipZipGenerationComplete(recordCount), active: Active) =>
      log.info(s"Generated $recordCount pockets")
      dsInfo.setState(MAPPABLE)
      dsInfo.setRecordCount(recordCount)
      if (datasetContext.sipMapperOpt.isDefined) {
        log.info(s"There is a mapper, so setting to processable")
        dsInfo.setState(PROCESSABLE)
      }
      else {
        log.info("No mapper, not processing")
      }

      // todo: figure this out
      //        val rawFile = datasetContext.createRawFile(datasetContext.pocketFile.getName)
      //        FileUtils.copyFile(datasetContext.pocketFile, rawFile)
      //        db.setStatus(RAW_POCKETS)
      active.childOpt.foreach(_ ! PoisonPill)
      goto(Idle) using Dormant

  }

  when(Analyzing) {

    case Event(AnalysisComplete(errorOption, processed), active: Active) =>
      val dsState = if (processed) ANALYZED else RAW_ANALYZED
      if (errorOption.isDefined) {
        dsInfo.removeState(dsState)
        datasetContext.dropTree()
      }
      else {
        dsInfo.setState(dsState)
      }
      active.childOpt.foreach(_ ! PoisonPill)
      goto(Idle) using Dormant

  }

  when(Processing) {

    case Event(ProcessingComplete(validRecords, invalidRecords, incrementalOpt), active: Active) =>
      dsInfo.setState(PROCESSED)
      if (incrementalOpt.isDefined) {
        dsInfo.setIncrementalProcessedRecordCounts(validRecords, invalidRecords)
        dsInfo.setState(PROCESSABLE)
        dsInfo.setState(INCREMENTAL_SAVED)
        val graphSaver = context.actorOf(GraphSaver.props(datasetContext, orgContext), "graph-saver")
        graphSaver ! SaveGraphs(incrementalOpt)
        active.childOpt.foreach(_ ! PoisonPill)
        goto(Saving) using Active(dsInfo.spec, Some(graphSaver), SAVING)
      }
      else {
        dsInfo.removeState(INCREMENTAL_SAVED)
        dsInfo.setProcessedRecordCounts(validRecords, invalidRecords)
        val ownerEmailOpt = Await.result(dsInfo.ownerEmailOpt, 2.seconds)
        mailService.sendProcessingCompleteMessage(dsInfo.spec, ownerEmailOpt, dsInfo.processedValidVal, dsInfo.processedInvalidVal)
        active.childOpt.foreach(_ ! PoisonPill)
        goto(Idle) using Dormant
      }
  }

  when(Saving) {

    case Event(GraphSaveComplete, active: Active) =>
      dsInfo.setState(SAVED)
      dsInfo.setRecordsSync(false)
      active.childOpt.foreach(_ ! PoisonPill)
      goto(Idle) using Dormant

  }

  when(Skosifying) {

    case Event(SkosificationComplete(skosifiedField), active: Active) =>
      log.info(s"Skosification complete: $skosifiedField")
      dsInfo.setProxyResourcesSync(false)
      dsInfo.setRecordsSync(false)
      active.childOpt.foreach(_ ! PoisonPill)
      goto(Idle) using Dormant

  }

  when(Categorizing) {

    case Event(CategoryCountComplete(spec, categoryCounts), active: Active) =>
      log.info(s"Category counting complete: $spec")
      context.parent ! CategoryCountComplete(spec, categoryCounts)
      active.childOpt.foreach(_ ! PoisonPill)
      goto(Idle) using Dormant

  }

  whenUnhandled {

    // this is because PeriodicSkosifyCheck may send multiple for us.  he'll be back
    case Event(StartSkosification(skosifiedField), active: Active) =>
      log.info(s"Ignoring skosification work for now: $skosifiedField")
      stay()

    case Event(tick: ProgressTick, active: Active) =>
      if (active.interrupt) {
        tick.reporterOpt.foreach(_.interrupt())
        stay() using active
      }
      else {
        val nextActive = active.copy(progressState = tick.progressState, progressType = tick.progressType, count = tick.count)
        broadcastProgress(nextActive)
        stay() using nextActive
      }

    case Event(Command(commandName), InError(message)) =>
      log.info(s"In error. Command name: $commandName")
      if (commandName == "clear error") {
        dsInfo.removeLiteralProp(datasetErrorMessage)
        goto(Idle) using Dormant
      }
      else {
        stay()
      }

    case Event(whatever, InError(_)) =>
      log.info(s"Not interested in: $whatever")
      stay()

    case Event(Command(commandName), active: Active) =>
      log.info(s"Active. Command name: $commandName")
      if (commandName == "interrupt") {
        stay() using active.copy(interrupt = true)
      }
      else {
        stay()
      }

    case Event(WorkFailure(message, exceptionOpt), active: Active) =>
      log.warning(s"Work failure [$message] while in [$active]")
      dsInfo.setError(s"While $stateName, failure: $message")
      exceptionOpt match {
        case Some(exception) => log.error(exception, message)
        case None => log.error(message)
      }
      val ownerEmail: Option[String] = Await.result(dsInfo.ownerEmailOpt, 2.seconds)
      mailService.sendProcessingErrorMessage(dsInfo.spec, ownerEmail, message, exceptionOpt)
      active.childOpt.foreach(_ ! PoisonPill)
      goto(Idle) using InError(message)

    case Event(WorkFailure(message, exceptionOpt), _) =>
      log.warning(s"Work failure $message while dormant")
      exceptionOpt match {
        case Some(exception) => log.error(exception, message)
        case None => log.error(message)
      }
      dsInfo.setError(s"While not active, failure: $message")
      goto(Idle) using InError(message)

    case Event(request, data) =>
      log.warning(s"Unhandled request $request in state $stateName/$data")
      stay()
  }

  onTransition {
    case fromState -> Idle =>
      log.info(s"State to Idle from $fromState")
      broadcastIdleState()
  }

}