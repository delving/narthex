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

import akka.actor.SupervisorStrategy.{Stop, Restart}
import akka.actor.OneForOneStrategy
import akka.actor._
import scala.concurrent.duration._
import analysis.Analyzer
import analysis.Analyzer.{AnalysisComplete, AnalyzeFile, AnalysisType}
import dataset.DatasetActor._
import dataset.DsInfo.DsState._
import dataset.SourceRepo.SourceFacts
import harvest.Harvester
import harvest.Harvester.{HarvestAdLib, HarvestComplete, HarvestJSON, HarvestPMH, HarvestDownloadLink}
import harvest.Harvesting.{JsonHarvestConfig, HarvestType}
import harvest.Harvesting.HarvestType._
import mapping.CategoryCounter.{CategoryCountComplete, CountCategories}
import mapping.Skosifier.SkosificationComplete
import mapping.{CategoryCounter, Skosifier}
import organization.OrgActor.EnqueueOperation
import organization.OrgContext
import org.apache.commons.io.FileUtils._
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{Json, Writes}
import record.SourceProcessor
import record.SourceProcessor._
import services.ProgressReporter.ProgressState._
import services.ProgressReporter.ProgressType._
import services.ProgressReporter.{ProgressState, ProgressType}
import services.{ActivityLogger, CredentialEncryption, MailService, ProgressReporter, TrendTrackingService}
import triplestore.GraphProperties._
import triplestore.GraphSaver
import triplestore.GraphSaver.{GraphSaveComplete, SaveGraphs}
import triplestore.Sparql.SkosifiedField

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.language.postfixOps
import scala.util.Try

object DatasetActor {

  private val logger = Logger(getClass)

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

  case class Active(spec: String,
                    childOpt: Option[ActorRef],
                    progressState: ProgressState,
                    progressType: ProgressType = TYPE_IDLE,
                    count: Int = 0,
                    errorCount: Int = 0,
                    errorRecoveryAttempts: Int = 0,
                    interrupt: Boolean = false,
                    stateStartTime: Long = System.currentTimeMillis(),
                    lastActivityTime: Long = System.currentTimeMillis(),
                    currentPage: Option[Int] = None,
                    totalPages: Option[Int] = None,
                    currentRecords: Option[Int] = None,
                    totalRecords: Option[Int] = None,
                    errorRecoveryUrl: Option[String] = None,
                    errorPagesTotal: Option[Int] = None,
                    errorPagesRecovered: Option[Int] = None)
      extends DatasetActorData

  implicit val activeWrites: Writes[Active] = new Writes[Active] {
    def writes(active: Active) = Json.obj(
      "datasetSpec" -> active.spec,
      "progressState" -> active.progressState.toString,
      "progressType" -> active.progressType.toString,
      "count" -> active.count,
      "errorCount" -> active.errorCount,
      "errorRecoveryAttempts" -> active.errorRecoveryAttempts,
      "interrupt" -> active.interrupt,
      "currentPage" -> active.currentPage,
      "totalPages" -> active.totalPages,
      "currentRecords" -> active.currentRecords,
      "totalRecords" -> active.totalRecords,
      "errorRecoveryUrl" -> active.errorRecoveryUrl,
      "errorPagesTotal" -> active.errorPagesTotal,
      "errorPagesRecovered" -> active.errorPagesRecovered
    )
  }

  case class InError(error: String) extends DatasetActorData

  case class InRetry(message: String, retryCount: Int) extends DatasetActorData

  // messages to receive

  trait HarvestStrategy

  case class ModifiedAfter(mod: DateTime, justDate: Boolean)
      extends HarvestStrategy

  case object Sample extends HarvestStrategy

  case object FromScratch extends HarvestStrategy

  case object FromScratchIncremental extends HarvestStrategy

  case class StartHarvest(strategy: HarvestStrategy)

  case class Command(name: String)

  case class StartAnalysis(processed: Boolean)

  case class StartSourceAnalysis()

  case class Scheduled(modifiedAfter: Option[DateTime], file: File)

  case class StartProcessing(scheduledOpt: Option[Scheduled])

  case class StartSaving(scheduledOpt: Option[Scheduled])

  case class StartSkosification(skosifiedField: SkosifiedField)

  case object StartCategoryCounting

  case class WorkFailure(message: String,
                         exceptionOpt: Option[Throwable] = None)

  case class ProgressTick(reporterOpt: Option[ProgressReporter],
                          progressState: ProgressState,
                          progressType: ProgressType = TYPE_IDLE,
                          count: Int = 0,
                          currentPage: Option[Int] = None,
                          totalPages: Option[Int] = None,
                          currentRecords: Option[Int] = None,
                          totalRecords: Option[Int] = None,
                          errorRecoveryUrl: Option[String] = None,
                          errorPagesTotal: Option[Int] = None,
                          errorPagesRecovered: Option[Int] = None)

  case object CheckForStuckState

  case object ForceReleaseAndReset

  case object GetCurrentState

  case class CurrentState(spec: String, isActive: Boolean)

  case class DatasetBecameActive(spec: String)

  case class DatasetBecameIdle(spec: String, recordCount: Option[Int] = None)

  // WebSocket broadcast messages - used with Akka event stream for reliable delivery
  // to all WebSocket actors regardless of their actor paths
  sealed trait WebSocketBroadcast
  case class WebSocketProgressBroadcast(active: Active) extends WebSocketBroadcast
  case class WebSocketIdleBroadcast(dsInfo: DsInfo) extends WebSocketBroadcast

  def props(datasetContext: DatasetContext,
            mailService: MailService,
            orgContext: OrgContext,
            harvestingExecutionContext: ExecutionContext) =
    Props(classOf[DatasetActor],
          datasetContext,
          mailService,
          orgContext,
          harvestingExecutionContext)
}

class DatasetActor(val datasetContext: DatasetContext,
                   mailService: MailService,
                   orgContext: OrgContext,
                   harvestingExecutionContext: ExecutionContext)
    extends LoggingFSM[DatasetActorState, DatasetActorData]
    with ActorLogging {

  import context.dispatcher

  // Track if we're in fast save mode (to continue processing after SIP generation)
  private var fastSaveScheduledOpt: Option[Scheduled] = None

  // Track if we're in fast process mode (process only, no save)
  private var fastProcessOnly: Boolean = false

  // Track if we should auto-continue to processing after first harvest (discovery imports)
  private var autoProcessAfterFirstHarvest: Boolean = false

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1.minute) {
    case _: OutOfMemoryError =>
      log.error("Child actor ran out of memory - stopping")
      Stop
    case _: StackOverflowError =>
      log.error("Child actor stack overflow - stopping")
      Stop
    case _: java.io.IOException =>
      log.warning("Child actor IO exception - restarting")
      Restart
    case throwable: Exception =>
      log.warning(s"Child actor exception: ${throwable.getMessage} - restarting")
      self ! WorkFailure(s"Child failure: $throwable", Some(throwable))
      Restart
    case throwable: Throwable =>
      log.error(s"Child actor fatal error: $throwable - stopping")
      self ! WorkFailure(s"Child failure: $throwable", Some(throwable))
      Stop
  }

  val dsInfo = datasetContext.dsInfo

  val errorMessage = dsInfo.getLiteralProp(datasetErrorMessage).getOrElse("")

  // Use Akka event stream for WebSocket broadcasts - this is more reliable than
  // actor selection which can fail due to path mismatches with Play's ActorFlow
  def broadcastIdleState() = {
    log.debug(s"Broadcasting idle state for dataset ${dsInfo.spec}")
    context.system.eventStream.publish(WebSocketIdleBroadcast(datasetContext.dsInfo))
  }

  def broadcastProgress(active: Active) = {
    context.system.eventStream.publish(WebSocketProgressBroadcast(active))
  }

  // Track child actors for proper cleanup
  private var childActors: Set[ActorRef] = Set.empty

  // Track workflow for activity logging
  private var currentWorkflowId: Option[String] = None
  private var workflowStartTime: Option[DateTime] = None
  private var operationStartTime: Option[DateTime] = None

  override def preStart(): Unit = {
    super.preStart()
    log.info(s"Starting DatasetActor for dataset: ${dsInfo.spec}")
  }

  override def postStop(): Unit = {
    log.info(s"Stopping DatasetActor for dataset: ${dsInfo.spec}")
    
    // CRITICAL: Always release BOTH semaphores when actor stops (safe to call even if not held)
    log.warning(s"DatasetActor stopped, ensuring both semaphores are released for: ${dsInfo.spec}")
    orgContext.semaphore.release(dsInfo.spec)
    orgContext.saveSemaphore.release(dsInfo.spec)
    
    // Stop all child actors to prevent resource leaks
    childActors.foreach { child =>
      log.debug(s"Stopping child actor: $child")
      context.stop(child)
    }
    childActors = Set.empty
    super.postStop()
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    log.warning(s"Restarting DatasetActor for dataset: ${dsInfo.spec} due to: ${reason.getMessage}")
    super.preRestart(reason, message)
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    log.info(s"Restarted DatasetActor for dataset: ${dsInfo.spec}")
  }

  private def createChildActor(props: Props, name: String): ActorRef = {
    val child = context.actorOf(props, name)
    childActors += child
    context.watch(child)
    child
  }

  private def isHarvestFailure(active: Active): Boolean = {
    active.progressState == HARVESTING
  }

  startWith(Idle, if (errorMessage.nonEmpty) InError(errorMessage) else Dormant)

  // Schedule periodic check for stuck states
  // Check every 5 minutes, max time = 20 minutes (stall timer handles hung harvests sooner)
  private val stuckStateCheckInterval = 5.minutes
  private val maxStateTime = 20.minutes

  log.info(s"Stuck state detection enabled: check every ${stuckStateCheckInterval.toMinutes} minutes, max state time ${maxStateTime.toMinutes} minutes")

  context.system.scheduler.scheduleWithFixedDelay(
    stuckStateCheckInterval,
    stuckStateCheckInterval,
    self,
    CheckForStuckState
  )

  when(Idle) {

    case Event(Command(commandName), Dormant) =>
      val replyTry = Try {

        def startHarvest(strategy: HarvestStrategy) = {
          val harvestTypeStringOpt = dsInfo.getLiteralProp(harvestType)

          log.info(s"Start harvest $strategy, type is $harvestTypeStringOpt")
          val harvestTypeOpt =
            harvestTypeStringOpt.flatMap(harvestTypeFromString)
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
              case _ =>
                //case FromScratchIncremental =>
                datasetContext.sourceRepoOpt match {
                  case Some(sourceRepo) =>
                    sourceRepo.clearData()
                  case None =>
                    // no source repo, use the default for the harvest type
                    datasetContext.createSourceRepo(SourceFacts(harvestType))
                }
              //case _ =>
            }

            self ! StartHarvest(strategy)
            "harvest started"


          } getOrElse {
            val message =
              s"Unable to harvest $datasetContext: unknown harvest type [$harvestTypeStringOpt]"
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

          case "disable dataset" =>
            Await.ready(datasetContext.disableDataSet, 2.minutes)
            broadcastIdleState()
            "disabled dataset"

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

          case "start first harvest with auto-process" =>
            // Used by discovery import to auto-continue to Make SIP → Process after harvest
            autoProcessAfterFirstHarvest = true
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

          case "start source analysis" =>
            self ! StartSourceAnalysis()
            "source analysis started"

          case "start saving" =>
            // full save, not incremental
            self ! StartSaving(None)
            "saving started"

          case cmd if cmd.startsWith("start fast save") =>
            // Smart workflow continuation based on specified or current state
            // Uses Scheduled context to enable automatic chaining (Process → Save)
            // Command format: "start fast save" or "start fast save from stateXxx"
            val fromState = if (cmd.contains(" from ")) {
              cmd.split(" from ").lastOption.getOrElse("")
            } else {
              "" // Auto-detect
            }

            val scheduledOpt = datasetContext.sourceRepoOpt
              .flatMap(_.latestSourceFileOpt)
              .map(file => Scheduled(None, file))

            scheduledOpt match {
              case Some(scheduled) =>
                // Use specified state or fall back to current state
                val targetState = fromState match {
                  case "stateAnalyzed" => ANALYZED
                  case "stateProcessed" => PROCESSED
                  case "stateProcessable" => PROCESSABLE
                  case "stateSourced" => SOURCED
                  case _ => dsInfo.getState() // Auto-detect
                }

                targetState match {
                  case ANALYZED =>
                    log.info(s"Fast save from ANALYZED: Save only (${dsInfo.spec})")
                    self ! StartSaving(Some(scheduled))
                    "Fast save: Saving"

                  case PROCESSED =>
                    log.info(s"Fast save from PROCESSED: Save (${dsInfo.spec})")
                    self ! StartSaving(Some(scheduled))
                    "Fast save: Saving"

                  case PROCESSABLE =>
                    log.info(s"Fast save from PROCESSABLE: Process → Save (${dsInfo.spec})")
                    self ! StartProcessing(Some(scheduled))
                    "Fast save: Process → Save"

                  case SOURCED =>
                    log.info(s"Fast save from SOURCED: Make SIP → Process → Save (${dsInfo.spec})")
                    // Set flag to continue processing after SIP generation
                    fastSaveScheduledOpt = Some(scheduled)
                    self ! GenerateSipZip
                    "Fast save: Make SIP → Process → Save"

                  case _ =>
                    "Dataset not ready for fast save"
                }

              case None =>
                "No source file found for fast save"
            }

          case "start fast process" =>
            // Workflow: Make SIP → Process (stops at PROCESSED, does NOT save)
            // Used by discovery import to prepare datasets for review
            val currentState = dsInfo.getState()

            currentState match {
              case PROCESSABLE =>
                log.info(s"Fast process from PROCESSABLE: Process only (${dsInfo.spec})")
                self ! StartProcessing(None)  // None = no auto-save
                "Fast process: Processing"

              case SOURCED =>
                log.info(s"Fast process from SOURCED: Make SIP → Process (${dsInfo.spec})")
                // Set flag to continue to processing (but not save) after SIP generation
                fastProcessOnly = true
                self ! GenerateSipZip
                "Fast process: Make SIP → Process"

              case PROCESSED | ANALYZED | SAVED =>
                "Dataset already processed"

              case _ =>
                s"Dataset not ready for fast process (current state: $currentState)"
            }

          case "start skosification" =>
            self ! StartSkosification
            "skosification started"

          case "refresh" =>
            log.info("refresh")
            // Invalidate cached model to ensure fresh data is read from triplestore
            // This is important when properties are changed externally (e.g., via setDatasetProperties)
            dsInfo.invalidateCachedModel()
            broadcastIdleState()
            "refreshed"

          case "resetToDormant" =>
            // Force release semaphores in case they're stuck from a previous crash
            log.info(s"resetToDormant: Force releasing semaphores for ${dsInfo.spec}")
            orgContext.semaphore.release(dsInfo.spec)
            orgContext.saveSemaphore.release(dsInfo.spec)
            broadcastIdleState()
            "reset to dormant"

          case "reset counts" =>
            // Reset all record counts to 0 (used when sample harvest returns 0 records)
            log.info(s"Resetting record counts for ${dsInfo.spec}")
            dsInfo.setRecordCount(0)
            dsInfo.setAcquisitionCounts(0, 0, 0, "harvest")
            dsInfo.removeState(SOURCED)
            dsInfo.removeState(MAPPABLE)
            dsInfo.setState(RAW)
            broadcastIdleState()
            "counts reset"

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
      // Auto-enable: remove disabled state when starting any workflow
      dsInfo.removeState(DISABLED)
      datasetContext.dropTree()
      def prop(p: NXProp) = dsInfo.getLiteralProp(p).getOrElse("")
      harvestTypeFromString(prop(harvestType)).map { harvestType =>
        log.info(s"Starting harvest $strategy with type $harvestType")
        strategy match {
          case FromScratch =>
            log.info("FromScratch: clearing all data")
            datasetContext.sourceRepoOpt match {
              case Some(sourceRepo) =>
                sourceRepo.clearData()
              case None =>
                // no source repo, use the default for the harvest type
                datasetContext.createSourceRepo(SourceFacts(harvestType))
            }
          case FromScratchIncremental =>
            log.info("FromScratchIncremental: clearing data for full periodic harvest")
            // Clear data like FromScratch - this is a FULL harvest, but uses incremental processing path
            datasetContext.sourceRepoOpt match {
              case Some(sourceRepo) =>
                sourceRepo.clearData()
              case None =>
                // no source repo, use the default for the harvest type
                datasetContext.createSourceRepo(SourceFacts(harvestType))
            }
          case _ =>
            // For other strategies like ModifiedAfter, Sample, etc
            log.info(s"Strategy $strategy: checking source repo")
            datasetContext.sourceRepoOpt match {
              case None =>
                datasetContext.createSourceRepo(SourceFacts(harvestType))
              case Some(_) =>
                // Keep existing data for incremental strategies
                log.debug("Source repo exists, keeping data")
            }
        }
        val (url, ds, pre, se, recordId, downloadLink) = (prop(harvestURL),
          prop(harvestDataset),
          prop(harvestPrefix),
          prop(harvestSearch),
          prop(harvestRecord),
          prop(harvestDownloadURL))

        // Get Basic Auth credentials if configured
        val credentials: Option[(String, String)] = {
          val username = prop(harvestUsername)
          val encryptedPassword = prop(harvestPassword)
          if (username.nonEmpty && encryptedPassword.nonEmpty) {
            val password = CredentialEncryption.decrypt(encryptedPassword, orgContext.appConfig.appSecret)
            Some((username, password))
          } else {
            None
          }
        }

        // Get API key if configured (for JSON harvest)
        val apiKey: Option[(String, String)] = {
          val paramName = prop(harvestApiKeyParam)
          val encryptedKey = prop(harvestApiKey)
          if (paramName.nonEmpty && encryptedKey.nonEmpty) {
            val keyValue = CredentialEncryption.decrypt(encryptedKey, orgContext.appConfig.appSecret)
            Some((paramName, keyValue))
          } else {
            None
          }
        }

        val kickoff = harvestType match {
          case DOWNLOAD => HarvestDownloadLink(strategy, downloadLink, dsInfo)
          case PMH   => HarvestPMH(strategy, url, ds, pre, recordId)
          case ADLIB => HarvestAdLib(strategy, url, ds, se, credentials)
          case JSON =>
            val jsonConfig = JsonHarvestConfig(
              itemsPath = prop(harvestJsonItemsPath),
              idPath = prop(harvestJsonIdPath),
              totalPath = Option(prop(harvestJsonTotalPath)).filter(_.nonEmpty),
              pageParam = Option(prop(harvestJsonPageParam)).filter(_.nonEmpty).getOrElse("page"),
              pageSizeParam = Option(prop(harvestJsonPageSizeParam)).filter(_.nonEmpty).getOrElse("pagesize"),
              pageSize = Try(prop(harvestJsonPageSize).toInt).getOrElse(50),
              detailPath = Option(prop(harvestJsonDetailPath)).filter(_.nonEmpty),
              skipDetail = prop(harvestJsonSkipDetail) == "true",
              xmlRoot = Option(prop(harvestJsonXmlRoot)).filter(_.nonEmpty).getOrElse("records"),
              xmlRecord = Option(prop(harvestJsonXmlRecord)).filter(_.nonEmpty).getOrElse("record")
            )
            HarvestJSON(strategy, url, jsonConfig, credentials, apiKey)
        }
        val harvester = createChildActor(
          Harvester.props(datasetContext,
                         orgContext.appConfig.harvestTimeOut,
                         orgContext.wsApi,
                         harvestingExecutionContext,
                         orgContext.appConfig.harvestPageStallTimeoutMinutes),
          "harvester")
        harvester ! kickoff
        goto(Harvesting) using Active(dsInfo.spec, Some(harvester), HARVESTING)
      } getOrElse {
        stay() using InError("Unable to determine harvest type")
      }

    // Handle StartHarvest when not in Dormant state (e.g., after previous harvest completed and dataset is in PROCESSED state)
    // This is the fix for stuck actors holding semaphores when scheduled harvests can't start
    case Event(StartHarvest(strategy), data) =>
      log.warning(s"Received StartHarvest($strategy) while in non-Dormant state: $data")
      log.info(s"Resetting to Dormant and reprocessing StartHarvest for dataset ${dsInfo.spec}")
      // First transition to Dormant state, then resend the StartHarvest message
      self ! StartHarvest(strategy)
      stay() using Dormant

    case Event(AdoptSource(file, orgContext), Dormant) =>
      // Auto-enable: remove disabled state when starting any workflow
      dsInfo.removeState(DISABLED)
      val sourceProcessor = createChildActor(
        SourceProcessor.props(datasetContext, orgContext),
        "source-adopter")
      sourceProcessor ! AdoptSource(file, orgContext)
      goto(Adopting) using Active(dsInfo.spec, Some(sourceProcessor), ADOPTING)

    case Event(GenerateSipZip, Dormant) =>
      // Auto-enable: remove disabled state when starting any workflow
      dsInfo.removeState(DISABLED)
      val sourceProcessor = createChildActor(
        SourceProcessor.props(datasetContext, orgContext),
        "source-generator")
      sourceProcessor ! GenerateSipZip
      goto(Generating) using Active(dsInfo.spec,
                                    Some(sourceProcessor),
                                    GENERATING)

    case Event(StartAnalysis(processed), Dormant) =>
      // Auto-enable: remove disabled state when starting any workflow
      dsInfo.removeState(DISABLED)
      log.info(s"Start analysis processed=$processed")
      if (processed) {
        val analyzer = createChildActor(
          Analyzer.props(datasetContext), "analyzer-processed")
        analyzer ! AnalyzeFile(datasetContext.processedRepo.baseOutput.xmlFile,
                               AnalysisType.PROCESSED)
        goto(Analyzing) using Active(dsInfo.spec, Some(analyzer), SPLITTING)
      } else {
        // Raw analysis: clean up all downstream workflow states (they're now stale)
        // This handles re-analysis scenarios where old states should be invalidated
        log.info(s"Cleaning up stale workflow states for ${dsInfo.spec}")
        dsInfo.removeState(SOURCED)
        dsInfo.removeState(MAPPABLE)
        dsInfo.removeState(PROCESSABLE)
        dsInfo.removeState(PROCESSED)
        dsInfo.removeState(ANALYZED)
        dsInfo.removeState(SAVED)
        dsInfo.removeState(INCREMENTAL_SAVED)
        dsInfo.removeLiteralProp(delimitersSet)

        val rawFile = datasetContext.rawXmlFile.getOrElse(
          throw new Exception(s"Unable to find 'raw' file to analyze"))
        val analyzer = createChildActor(
          Analyzer.props(datasetContext), "analyzer-raw")
        analyzer ! AnalyzeFile(rawFile, AnalysisType.RAW)
        goto(Analyzing) using Active(dsInfo.spec, Some(analyzer), SPLITTING)
      }

    case Event(StartSourceAnalysis(), Dormant) =>
      // Auto-enable: remove disabled state when starting any workflow
      dsInfo.removeState(DISABLED)
      datasetContext.dropSourceTree()  // Clean source tree before analysis
      log.info(s"Start source analysis for ${dsInfo.spec}")

      // Use source.xml.gz from the SIP file (created during SIP generation)
      datasetContext.sipRepo.latestSipOpt.flatMap(_.copySourceToTempFile) match {
        case Some(sourceFile) =>
          val analyzer = createChildActor(
            Analyzer.props(datasetContext), "analyzer-source")
          analyzer ! AnalyzeFile(sourceFile, AnalysisType.SOURCE,
                                 Some(datasetContext.sourceTreeRoot),
                                 Some(datasetContext.sourceIndex))
          goto(Analyzing) using Active(dsInfo.spec, Some(analyzer), SPLITTING)
        case None =>
          log.warning(s"No SIP with source.xml.gz available for ${dsInfo.spec}")
          stay() using Dormant
      }

    case Event(StartProcessing(scheduledOpt), Dormant) =>
      // Auto-enable: remove disabled state when starting any workflow
      dsInfo.removeState(DISABLED)
      val sourceProcessor = createChildActor(
        SourceProcessor.props(datasetContext, orgContext),
        "source-processor")
      sourceProcessor ! Process(scheduledOpt)
      goto(Processing) using Active(dsInfo.spec,
                                    Some(sourceProcessor),
                                    PROCESSING)

    case Event(StartSaving(scheduledOpt), Dormant) =>
      // Auto-enable: remove disabled state when starting any workflow
      dsInfo.removeState(DISABLED)
      // OrgActor already manages concurrency via queue - just track save for status reporting
      if (!orgContext.saveSemaphore.tryAcquire(dsInfo.spec)) {
        log.warning(s"saveSemaphore already held for ${dsInfo.spec} - continuing anyway (queue should prevent this)")
      }
      log.info(s"Starting save for ${dsInfo.spec}")
      val graphSaver = createChildActor(
        GraphSaver.props(datasetContext, orgContext),
        "graph-saver")
      graphSaver ! SaveGraphs(scheduledOpt)
      goto(Saving) using Active(dsInfo.spec, Some(graphSaver), PROCESSING)

    case Event(StartSkosification(skosifiedField), Dormant) =>
      // Auto-enable: remove disabled state when starting any workflow
      dsInfo.removeState(DISABLED)
      val skosifier = createChildActor(
        Skosifier.props(dsInfo, orgContext), "skosifier")
      skosifier ! skosifiedField
      goto(Skosifying) using Active(dsInfo.spec, Some(skosifier), SKOSIFYING)

    case Event(StartCategoryCounting, Dormant) =>
      // Auto-enable: remove disabled state when starting any workflow
      dsInfo.removeState(DISABLED)
      if (datasetContext.processedRepo.nonEmpty) {
        implicit val ts = orgContext.ts
        val categoryCounter = createChildActor(
          CategoryCounter.props(dsInfo,
                               datasetContext.processedRepo,
                               orgContext),
                          "category-counter")
        categoryCounter ! CountCategories
        goto(Categorizing) using Active(dsInfo.spec,
                                        Some(categoryCounter),
                                        CATEGORIZING)
      } else {
        stay() using InError(s"No source file for categorizing $datasetContext")
      }

  }

  when(Harvesting) {

    case Event(HarvestComplete(strategy, fileOpt, noRecordsMatch),
               active: Active) =>
      def processIncremental(fileOpt: Option[File],
                             noRecordsMatch: Boolean,
                             mod: Option[DateTime]) = {
        // Determine if there will be more work (processing/saving)
        val hasMoreWork = !noRecordsMatch && fileOpt.isDefined

        // Only release semaphore if no more work to do
        // If there's more work, semaphore will be released by GraphSaver
        if (!hasMoreWork) {
          orgContext.semaphore.release(dsInfo.spec)
        }

        noRecordsMatch match {
          case true =>
            logger.debug(
              "NoRecordsMatch, so setting state to Incremental Saved")
            dsInfo.setState(INCREMENTAL_SAVED)
            if (dsInfo
                  .getLiteralProp(harvestIncrementalMode)
                  .getOrElse("false") != "true") {
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

        fileOpt match {
          case Some(file) =>
            // Always regenerate SIP after incremental harvest to keep pockets.xml current
            log.info(s"Incremental harvest complete, regenerating SIP from all source files")
            if (datasetContext.sipMapperOpt.isDefined) {
              // Set flag to continue to processing after SIP generation
              fastSaveScheduledOpt = Some(Scheduled(mod, file))
            }
            self ! GenerateSipZip
          case None =>
            log.info("No incremental file, back to sleep")
        }
      }
      strategy match {
        case Sample =>
          // Sample harvests bypass semaphore, no release needed
          // Note: We always set RAW state even with noRecordsMatch so frontend can detect the harvest completed
          // The frontend will show a modal when stateRaw is set but acquiredRecordCount is 0
          dsInfo.setState(RAW)
        case FromScratch =>
          if (noRecordsMatch) {
            // Full harvest returned no records - all records depublished
            // Keep SAVED state so dataset stays in harvest cycle for auto-recovery
            // Reset counts to 0 to reflect current state
            log.info(s"Full harvest (FromScratch) returned noRecordsMatch for ${dsInfo.spec} - resetting counts to 0")
            dsInfo.setRecordCount(0)
            dsInfo.setProcessedRecordCounts(0, 0)
            orgContext.semaphore.release(dsInfo.spec)
          } else {
            dsInfo.removeState(SAVED)
            dsInfo.removeState(ANALYZED)
            dsInfo.removeState(INCREMENTAL_SAVED)
            dsInfo.removeState(PROCESSED)
            dsInfo.setState(SOURCED)
            dsInfo.setLastHarvestTime(incremental = false)

            // Auto-continue to Make SIP → Process if flag is set (discovery imports)
            if (autoProcessAfterFirstHarvest) {
              autoProcessAfterFirstHarvest = false  // Reset flag
              log.info(s"Auto-continuing to Make SIP → Process for ${dsInfo.spec}")
              fastProcessOnly = true  // Use existing flag to stop at PROCESSED (no save)
              self ! GenerateSipZip
              // Don't release semaphore - will be released after processing completes
            } else {
              // Release semaphore since FromScratch harvest is complete
              orgContext.semaphore.release(dsInfo.spec)
            }
          }

        case ModifiedAfter(mod, _) =>
          processIncremental(fileOpt, noRecordsMatch, Some(mod))

        case FromScratchIncremental =>
          if (noRecordsMatch) {
            // Full harvest returned no records - all records depublished
            // Keep SAVED state so dataset stays in harvest cycle for auto-recovery
            // Reset counts to 0 to reflect current state
            log.info(s"Full harvest (FromScratchIncremental) returned noRecordsMatch for ${dsInfo.spec} - resetting counts and disabling index.")
            dsInfo.setRecordCount(0)
            dsInfo.setProcessedRecordCounts(0, 0)
            dsInfo.disableInNaveIndex()
            orgContext.semaphore.release(dsInfo.spec)
          } else {
            processIncremental(fileOpt, noRecordsMatch, None)
            dsInfo.updatedSpecCountFromFile(dsInfo.spec,
                                          orgContext.appConfig.narthexDataDir,
                                          orgContext.appConfig.orgId)
          }
      }
      active.childOpt.foreach(_ ! PoisonPill)
      goto(Idle) using Dormant
  }

  when(Adopting) {

    case Event(SourceAdoptionComplete(file), active: Active) =>
      dsInfo.setState(SOURCED)
      if (datasetContext.sipRepo.latestSipOpt.isDefined)
        dsInfo.setState(PROCESSABLE)
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

      // Set acquisition tracking counts
      datasetContext.sourceRepoOpt.foreach { sourceRepo =>
        val deletedCount = sourceRepo.deletedCount
        val acquiredCount = recordCount + deletedCount  // Source + deleted = total acquired
        // Determine acquisition method: "harvest" if harvest properties exist, otherwise "upload"
        val acquisitionMethod = dsInfo.getLiteralProp(harvestType).map(_ => "harvest").getOrElse("upload")
        dsInfo.setAcquisitionCounts(acquiredCount, deletedCount, recordCount, acquisitionMethod)
        if (deletedCount > 0) {
          log.info(s"Acquisition counts set: acquired=$acquiredCount, deleted=$deletedCount, source=$recordCount, method=$acquisitionMethod")
        }
      }

      if (datasetContext.sipMapperOpt.isDefined) {
        // Don't overwrite PROCESSED state (e.g., from upload-processed endpoint)
        val currentState = dsInfo.getState()
        if (currentState != PROCESSED) {
          log.info(s"There is a mapper, so setting to processable")
          dsInfo.setState(PROCESSABLE)
        } else {
          log.info(s"Keeping PROCESSED state (externally processed)")
        }
      } else {
        log.info("No mapper, not processing")
      }

      // todo: figure this out
      //        val rawFile = datasetContext.createRawFile(datasetContext.pocketFile.getName)
      //        FileUtils.copyFile(datasetContext.pocketFile, rawFile)
      //        db.setStatus(RAW_POCKETS)
      active.childOpt.foreach(_ ! PoisonPill)

      // Check if we're in fast save mode and should continue to processing
      fastSaveScheduledOpt match {
        case Some(scheduled) if datasetContext.sipMapperOpt.isDefined =>
          log.info(s"Fast save mode: continuing to processing after SIP generation")
          fastSaveScheduledOpt = None // Clear the flag
          fastProcessOnly = false
          self ! StartProcessing(Some(scheduled))
          goto(Idle) using Dormant
        case _ if fastProcessOnly && datasetContext.sipMapperOpt.isDefined =>
          log.info(s"Fast process mode: continuing to processing after SIP generation (no save)")
          fastProcessOnly = false
          fastSaveScheduledOpt = None
          self ! StartProcessing(None)  // None = no auto-save after processing
          goto(Idle) using Dormant
        case _ =>
          fastSaveScheduledOpt = None // Clear the flag if set
          fastProcessOnly = false
          // Release semaphore since we're not continuing to processing
          // The semaphore was held by processIncremental or Adopting handler
          orgContext.semaphore.release(dsInfo.spec)
          goto(Idle) using Dormant
      }

  }

  when(Analyzing) {

    case Event(AnalysisComplete(errorOption, analysisType), active: Active) =>
      val dsState = analysisType match {
        case AnalysisType.PROCESSED => ANALYZED
        case AnalysisType.SOURCE => SOURCE_ANALYZED
        case AnalysisType.RAW => RAW_ANALYZED
      }
      if (errorOption.isDefined) {
        dsInfo.removeState(dsState)
        analysisType match {
          case AnalysisType.SOURCE => datasetContext.dropSourceTree()
          case _ => datasetContext.dropTree()
        }
      } else {
        dsInfo.setState(dsState)
      }
      active.childOpt.foreach(_ ! PoisonPill)
      goto(Idle) using Dormant

  }

  when(Processing) {

    case Event(ProcessingComplete(validRecords, invalidRecords, scheduledOpt),
               active: Active) =>
      dsInfo.setState(PROCESSED)
      if (scheduledOpt.isDefined) {
        dsInfo.setIncrementalProcessedRecordCounts(validRecords, invalidRecords)
        // Update total record count from filesystem for scheduled/incremental harvests
        val (_, _, totalSourceRecords) = dsInfo.updatedSpecCountFromFile(dsInfo.spec,
                                        orgContext.appConfig.narthexDataDir,
                                        orgContext.appConfig.orgId)

        // Set acquisition counts for incremental harvests (since SipZipGeneration was skipped)
        datasetContext.sourceRepoOpt.foreach { sourceRepo =>
          val deletedCount = sourceRepo.deletedCount
          val acquiredCount = totalSourceRecords + deletedCount
          val acquisitionMethod = dsInfo.getLiteralProp(harvestType).map(_ => "harvest").getOrElse("upload")
          dsInfo.setAcquisitionCounts(acquiredCount, deletedCount, totalSourceRecords, acquisitionMethod)
          if (deletedCount > 0 || acquiredCount > 0) {
            log.info(s"Acquisition counts set during ProcessingComplete: acquired=$acquiredCount, deleted=$deletedCount, source=$totalSourceRecords, method=$acquisitionMethod")
          }
        }
        val graphSaver = createChildActor(
          GraphSaver.props(datasetContext, orgContext),
          "graph-saver")
        graphSaver ! SaveGraphs(scheduledOpt)
        dsInfo.setState(INCREMENTAL_SAVED)
        active.childOpt.foreach(_ ! PoisonPill)
        goto(Saving) using Active(dsInfo.spec, Some(graphSaver), SAVING)
      } else {
        dsInfo.removeState(INCREMENTAL_SAVED)
        dsInfo.setProcessedRecordCounts(validRecords, invalidRecords)
        // Success emails disabled - only send emails on errors
        active.childOpt.foreach(_ ! PoisonPill)
        // Release semaphore since we're not going through GraphSaver
        orgContext.semaphore.release(dsInfo.spec)
        goto(Idle) using Dormant
      }
  }

  when(Saving) {

    case Event(GraphSaveComplete, active: Active) =>
      log.info(s"GraphSaveComplete received for $dsInfo.spec")
      // Note: GraphSaver already released semaphores, so don't double-release
      dsInfo.setState(SAVED)
      dsInfo.setRecordsSync(false)

      // Capture trend snapshot after successful save (change-gated)
      try {
        val sourceRecords = dsInfo.getLiteralProp(sourceRecordCount).map(_.toInt).getOrElse(0)
        val acquiredRecords = dsInfo.getLiteralProp(acquiredRecordCount).map(_.toInt).getOrElse(0)
        val deletedRecords = dsInfo.getLiteralProp(deletedRecordCount).map(_.toInt).getOrElse(0)
        val validRecords = dsInfo.getLiteralProp(processedValid).map(_.toInt).getOrElse(0)
        val invalidRecords = dsInfo.getLiteralProp(processedInvalid).map(_.toInt).getOrElse(0)
        val indexedRecords = validRecords

        TrendTrackingService.captureEventSnapshot(
          trendsLog = datasetContext.trendsLog,
          event = "save",
          sourceRecords = sourceRecords,
          acquiredRecords = acquiredRecords,
          deletedRecords = deletedRecords,
          validRecords = validRecords,
          invalidRecords = invalidRecords,
          indexedRecords = indexedRecords
        )
      } catch {
        case e: Exception =>
          log.warning(s"Failed to capture trend snapshot: ${e.getMessage}")
      }

      active.childOpt.foreach { child =>
        childActors -= child
        context.stop(child) // Use context.stop instead of PoisonPill for cleaner termination
      }
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

  // Handlers for InRetry state
  when(Idle) {

    // Handle commands while in retry state
    case Event(Command(commandName), InRetry(message, retryCount)) =>
      log.info(s"In retry mode (attempt #$retryCount). Command: $commandName")

      commandName match {
        case "stop retrying" =>
          log.info(s"User stopped retrying after $retryCount attempts")
          dsInfo.clearRetryState()
          dsInfo.setError(s"Harvest stopped after $retryCount retry attempts: $message")
          mailService.sendProcessingErrorMessage(dsInfo.spec, message, None)
          goto(Idle) using InError(message)

        case "retry now" =>
          log.info(s"User triggered immediate retry (attempt #${retryCount + 1})")
          dsInfo.incrementRetryCount()
          // Route through OrgActor for proper queue management
          val strategy = FromScratch
          orgContext.orgActor ! EnqueueOperation(dsInfo.spec, StartHarvest(strategy), "manual")
          stay()

        case "clear error" =>
          // User wants to completely clear retry state
          log.info("User cleared retry state")
          dsInfo.clearRetryState()
          goto(Idle) using Dormant

        case _ =>
          log.info(s"Ignoring command '$commandName' while in retry mode")
          stay()
      }

    // Handle StartHarvest while in retry mode (triggered by PeriodicHarvest or manual retry)
    case Event(StartHarvest(strategy), InRetry(message, retryCount)) =>
      log.info(s"Starting retry harvest attempt #${retryCount + 1} for ${dsInfo.spec}")

      // Increment retry count
      val newCount = dsInfo.incrementRetryCount()

      // Start harvest (reuse existing harvest startup logic)
      def prop(p: NXProp) = dsInfo.getLiteralProp(p).getOrElse("")
      harvestTypeFromString(prop(harvestType)).map { harvestType =>
        datasetContext.dropTree()

        // Prepare source repo based on strategy
        strategy match {
          case FromScratch =>
            log.info("FromScratch: clearing all data")
            datasetContext.sourceRepoOpt match {
              case Some(sourceRepo) => sourceRepo.clearData()
              case None => datasetContext.createSourceRepo(SourceFacts(harvestType))
            }
          case FromScratchIncremental =>
            log.info("FromScratchIncremental: clearing data for full periodic harvest")
            datasetContext.sourceRepoOpt match {
              case Some(sourceRepo) => sourceRepo.clearData()
              case None => datasetContext.createSourceRepo(SourceFacts(harvestType))
            }
          case _ =>
            log.info(s"Strategy $strategy: checking source repo")
            datasetContext.sourceRepoOpt match {
              case None => datasetContext.createSourceRepo(SourceFacts(harvestType))
              case Some(_) => log.debug("Source repo exists, keeping data")
            }
        }

        val (url, ds, pre, se, recordId, downloadLink) = (prop(harvestURL),
          prop(harvestDataset),
          prop(harvestPrefix),
          prop(harvestSearch),
          prop(harvestRecord),
          prop(harvestDownloadURL))

        // Get Basic Auth credentials if configured
        val credentials: Option[(String, String)] = {
          val username = prop(harvestUsername)
          val encryptedPassword = prop(harvestPassword)
          if (username.nonEmpty && encryptedPassword.nonEmpty) {
            val password = CredentialEncryption.decrypt(encryptedPassword, orgContext.appConfig.appSecret)
            Some((username, password))
          } else {
            None
          }
        }

        // Get API key if configured (for JSON harvest)
        val apiKey: Option[(String, String)] = {
          val paramName = prop(harvestApiKeyParam)
          val encryptedKey = prop(harvestApiKey)
          if (paramName.nonEmpty && encryptedKey.nonEmpty) {
            val keyValue = CredentialEncryption.decrypt(encryptedKey, orgContext.appConfig.appSecret)
            Some((paramName, keyValue))
          } else {
            None
          }
        }

        val kickoff = harvestType match {
          case DOWNLOAD => HarvestDownloadLink(strategy, downloadLink, dsInfo)
          case PMH   => HarvestPMH(strategy, url, ds, pre, recordId)
          case ADLIB => HarvestAdLib(strategy, url, ds, se, credentials)
          case JSON =>
            val jsonConfig = JsonHarvestConfig(
              itemsPath = prop(harvestJsonItemsPath),
              idPath = prop(harvestJsonIdPath),
              totalPath = Option(prop(harvestJsonTotalPath)).filter(_.nonEmpty),
              pageParam = Option(prop(harvestJsonPageParam)).filter(_.nonEmpty).getOrElse("page"),
              pageSizeParam = Option(prop(harvestJsonPageSizeParam)).filter(_.nonEmpty).getOrElse("pagesize"),
              pageSize = Try(prop(harvestJsonPageSize).toInt).getOrElse(50),
              detailPath = Option(prop(harvestJsonDetailPath)).filter(_.nonEmpty),
              skipDetail = prop(harvestJsonSkipDetail) == "true",
              xmlRoot = Option(prop(harvestJsonXmlRoot)).filter(_.nonEmpty).getOrElse("records"),
              xmlRecord = Option(prop(harvestJsonXmlRecord)).filter(_.nonEmpty).getOrElse("record")
            )
            HarvestJSON(strategy, url, jsonConfig, credentials, apiKey)
        }
        val harvester = createChildActor(
          Harvester.props(datasetContext,
                         orgContext.appConfig.harvestTimeOut,
                         orgContext.wsApi,
                         harvestingExecutionContext,
                         orgContext.appConfig.harvestPageStallTimeoutMinutes),
          "harvester")
        harvester ! kickoff
        goto(Harvesting) using Active(dsInfo.spec, Some(harvester), HARVESTING)
      } getOrElse {
        log.error(s"Unable to determine harvest type for retry")
        stay() using InRetry(message, newCount)
      }

    // Handle another failure while already in retry mode
    case Event(WorkFailure(newMessage, exceptionOpt), InRetry(oldMessage, retryCount)) =>
      val maxRetries = orgContext.appConfig.harvestMaxRetries
      log.warning(s"Retry attempt #$retryCount failed (max: $maxRetries): $newMessage")

      exceptionOpt match {
        case Some(exception) => log.error(exception, newMessage)
        case None            => log.error(newMessage)
      }

      if (retryCount >= maxRetries) {
        // Max retries exhausted - send email and go to error state
        log.error(s"Max retries ($maxRetries) exhausted for ${dsInfo.spec}: $newMessage")
        dsInfo.clearRetryState()
        dsInfo.setError(s"Harvest failed after $retryCount retry attempts: $newMessage")
        mailService.sendProcessingErrorMessage(dsInfo.spec,
          s"Harvest failed after $retryCount retry attempts: $newMessage", exceptionOpt)
        orgContext.semaphore.release(dsInfo.spec)
        goto(Idle) using InError(newMessage)
      } else {
        // Update retry state with new message but keep count
        dsInfo.setInRetry(newMessage, retryCount)
        // Stay in retry mode, PeriodicHarvest will trigger next attempt
        orgContext.semaphore.release(dsInfo.spec)
        stay() using InRetry(newMessage, retryCount)
      }
  }

  when(Harvesting) {
    // Handle successful harvest completion while in retry mode
    case Event(HarvestComplete(strategy, fileOpt, noRecordsMatch), InRetry(message, retryCount)) =>
      log.info(s"Harvest succeeded after $retryCount retry attempts for ${dsInfo.spec}")
      dsInfo.clearRetryState()
      orgContext.semaphore.release(dsInfo.spec)

      // Continue with normal harvest completion flow
      if (noRecordsMatch) {
        goto(Idle) using Dormant
      } else {
        fileOpt match {
          case Some(file) =>
            dsInfo.setState(RAW)
            val analyzer = createChildActor(Analyzer.props(datasetContext), "analyzer")
            analyzer ! AnalyzeFile(file, AnalysisType.RAW)
            goto(Analyzing) using Active(dsInfo.spec, Some(analyzer), SPLITTING)
          case None =>
            goto(Idle) using Dormant
        }
      }
  }

  whenUnhandled {

    case Event(GetCurrentState, _) =>
      // Return whether this actor is currently active (not in Idle state)
      val isActive = stateName != Idle
      sender() ! CurrentState(dsInfo.spec, isActive)
      stay()

    case Event(CheckForStuckState, active: Active) =>
      val currentTime = System.currentTimeMillis()
      val timeSinceActivity = currentTime - active.lastActivityTime
      val timeInState = currentTime - active.stateStartTime
      if (timeSinceActivity > maxStateTime.toMillis) {
        log.error(s"Dataset ${dsInfo.spec} stuck in state $stateName - no activity for ${timeSinceActivity / 1000 / 60} minutes - forcing reset")
        log.error(s"Active state details: progressState=${active.progressState}, progressType=${active.progressType}, childOpt=${active.childOpt}")
        log.error(s"State duration: ${timeInState / 1000 / 60} minutes, last activity: ${timeSinceActivity / 1000 / 60} minutes ago")
        // Send force reset message to self
        self ! ForceReleaseAndReset
      }
      stay()

    case Event(CheckForStuckState, _) =>
      // Not in active state, nothing to check
      stay()

    case Event(ForceReleaseAndReset, active: Active) =>
      log.warning(s"Force releasing semaphores and resetting dataset ${dsInfo.spec}")
      // Kill any child actors
      active.childOpt.foreach { child =>
        log.warning(s"Killing stuck child actor: $child")
        child ! PoisonPill
      }
      // Release semaphores
      orgContext.semaphore.release(dsInfo.spec)
      orgContext.saveSemaphore.release(dsInfo.spec)
      // Set error state
      dsInfo.setError(s"Actor stuck in state $stateName for more than ${maxStateTime.toMinutes} minutes - forced reset")
      // Return to idle
      goto(Idle) using InError(s"Stuck in $stateName - forced reset")

    case Event(ForceReleaseAndReset, _) =>
      log.warning(s"Force release requested but not in active state - releasing semaphores anyway")
      orgContext.semaphore.release(dsInfo.spec)
      orgContext.saveSemaphore.release(dsInfo.spec)
      stay()

    case Event(Terminated(actor), _) =>
      log.info(s"Child actor terminated: $actor")
      // Remove from tracked children and continue
      childActors -= actor
      stay()

    // Handle indexing completion notification from Hub3 webhook
    case Event(indexing: webhook.IndexingComplete, _) =>
      log.info(s"Received indexing completion for ${dsInfo.spec}: " +
        s"type=${indexing.notificationType}, indexed=${indexing.recordsIndexed}, " +
        s"expected=${indexing.recordsExpected}, orphans=${indexing.orphansDeleted}, " +
        s"errors=${indexing.errorCount}")

      // Store indexing results in the triple store
      dsInfo.setIndexingResults(
        status = indexing.notificationType,
        recordsIndexed = indexing.recordsIndexed,
        recordsExpected = indexing.recordsExpected,
        orphansDeleted = indexing.orphansDeleted,
        errorCount = indexing.errorCount,
        revision = indexing.revision,
        message = indexing.message,
        timestamp = indexing.timestamp
      )

      // Log any indexing errors to activity log
      indexing.errors.foreach { errors =>
        if (errors.nonEmpty) {
          ActivityLogger.logIndexingErrors(datasetContext.activityLog, errors)
        }
      }

      // Broadcast updated state to WebSocket clients
      broadcastIdleState()
      stay()

    // this is because PeriodicSkosifyCheck may send multiple for us.  he'll be back
    case Event(StartSkosification(skosifiedField), active: Active) =>
      log.info(s"Ignoring skosification work for now: $skosifiedField")
      stay()

    case Event(tick: ProgressTick, active: Active) =>
      if (active.interrupt) {
        tick.reporterOpt.foreach(_.interrupt())
        stay() using active
      } else {
        val nextActive = active.copy(progressState = tick.progressState,
                                     progressType = tick.progressType,
                                     count = tick.count,
                                     lastActivityTime = System.currentTimeMillis(),
                                     currentPage = tick.currentPage,
                                     totalPages = tick.totalPages,
                                     currentRecords = tick.currentRecords,
                                     totalRecords = tick.totalRecords,
                                     errorRecoveryUrl = tick.errorRecoveryUrl,
                                     errorPagesTotal = tick.errorPagesTotal,
                                     errorPagesRecovered = tick.errorPagesRecovered)
        broadcastProgress(nextActive)
        stay() using nextActive
      }

    case Event(Command(commandName), InError(message)) =>
      log.info(s"In error. Command name: $commandName")
      if (commandName == "clear error") {
        dsInfo.removeLiteralProp(datasetErrorMessage)
        log.info(s"clear error so releasing semaphore if set")
        orgContext.semaphore.release(dsInfo.spec)
        orgContext.saveSemaphore.release(dsInfo.spec)
        goto(Idle) using Dormant
      } else {
        log.info(s"in error so releasing semaphore if set")
        orgContext.semaphore.release(dsInfo.spec)
        orgContext.saveSemaphore.release(dsInfo.spec)
        stay()
      }

    case Event(whatever, InError(_)) =>
      log.info(s"Not interested in: $whatever")
        log.info(s"in error so releasing semaphore if set")
        orgContext.semaphore.release(dsInfo.spec)
        orgContext.saveSemaphore.release(dsInfo.spec)
      stay()

    case Event(Command(commandName), active: Active) =>
      if (commandName == "refresh") {
        log.warning("refresh unhandled command")
        stay()
      } else if (commandName == "interrupt") {
        log.info(s"Interrupt command received for ${dsInfo.spec} - setting interrupt flag")
        // Set the interrupt flag so next ProgressTick will trigger interruption
        // The child actor will check this flag and stop gracefully
        stay() using active.copy(interrupt = true)
      } else {
        log.warning(s"Active unhandled Command name: $commandName (reset to idle/dormant)")
        // kill active actors
        active.childOpt.foreach(_ ! PoisonPill)
        // Release semaphores since we're aborting the operation
        orgContext.semaphore.release(dsInfo.spec)
        orgContext.saveSemaphore.release(dsInfo.spec)
        goto(Idle) using Dormant
      }



    case Event(WorkFailure(message, exceptionOpt), active: Active) =>
      log.warning(s"Work failure [$message] while in [$active]")

      // Check if this is a harvest failure (candidate for retry)
      if (isHarvestFailure(active)) {
        // Check if we're already in retry mode (preserve/use current count)
        val currentRetryCount = if (dsInfo.isInRetry) dsInfo.getRetryCount else 0
        val maxRetries = orgContext.appConfig.harvestMaxRetries

        if (currentRetryCount > 0) {
          log.info(s"Retry harvest #$currentRetryCount failed for ${dsInfo.spec}")
        } else {
          log.info(s"Harvest failure detected, entering retry mode for ${dsInfo.spec}")
        }

        // Check if max retries exceeded
        if (currentRetryCount >= maxRetries) {
          log.warning(s"Max retries ($maxRetries) reached for ${dsInfo.spec}, entering error state")
          dsInfo.clearRetryState()
          dsInfo.setError(s"Harvest failed after $currentRetryCount retry attempts: $message")
          mailService.sendProcessingErrorMessage(dsInfo.spec, s"Harvest failed after $currentRetryCount retry attempts: $message", exceptionOpt)
          active.childOpt.foreach(_ ! PoisonPill)
          orgContext.semaphore.release(dsInfo.spec)
          orgContext.saveSemaphore.release(dsInfo.spec)
          goto(Idle) using InError(message)
        } else {
          // Set retry state in dataset properties (preserve current count)
          dsInfo.setInRetry(message, currentRetryCount)

          // Log the error but don't send email yet (will send after max retries or manual stop)
          exceptionOpt match {
            case Some(exception) => log.error(exception, message)
            case None            => log.error(message)
          }

          // Release semaphore so PeriodicHarvest can re-acquire for retry
          active.childOpt.foreach(_ ! PoisonPill)
          orgContext.semaphore.release(dsInfo.spec)
          orgContext.saveSemaphore.release(dsInfo.spec)

          // Transition to retry state (not error state)
          goto(Idle) using InRetry(message, currentRetryCount)
        }

      } else {
        // Non-harvest failure - use existing error handling
        dsInfo.setError(s"While $stateName, failure: $message")
        exceptionOpt match {
          case Some(exception) => log.error(exception, message)
          case None            => log.error(message)
        }
        mailService.sendProcessingErrorMessage(dsInfo.spec, message, exceptionOpt)
        active.childOpt.foreach(_ ! PoisonPill)
        // Release semaphores since operation failed
        orgContext.semaphore.release(dsInfo.spec)
        orgContext.saveSemaphore.release(dsInfo.spec)
        goto(Idle) using InError(message)
      }

    case Event(WorkFailure(message, exceptionOpt), _) =>
      log.warning(s"Work failure $message while dormant")
      exceptionOpt match {
        case Some(exception) => log.error(exception, message)
        case None            => log.error(message)
      }
      dsInfo.setError(s"While not active, failure: $message")
      goto(Idle) using InError(message)

    case Event(request, data) =>
      log.warning(s"Unhandled request $request in state $stateName/$data")
      stay()
  }

  onTransition {
    case fromState -> toState =>
      log.info(s"State transition: $fromState -> $toState for dataset ${dsInfo.spec}")
      stateData match {
        case active: Active =>
          log.debug(s"State data: progressState=${active.progressState}, progressType=${active.progressType}, childPresent=${active.childOpt.isDefined}")
        case Dormant =>
          log.debug("State data: Dormant")
        case InError(error) =>
          log.warning(s"State data: InError($error)")
      }

      // Track operation state for restart recovery and activity logging
      if (toState == Idle) {
        // Capture trigger before clearing (needed for logging)
        val trigger = dsInfo.getOperationTrigger.getOrElse("manual")
        val operation = fromState.toString.toUpperCase

        // Clear operation tracking BEFORE broadcast so UI sees cleared state
        dsInfo.clearOperation()
        val completedStartTime = operationStartTime
        operationStartTime = None

        // Broadcast idle state (now with cleared operation)
        broadcastIdleState()

        // Determine record count based on operation type (calculate before logging so it's available for idle message)
        val recordCountOpt: Option[Int] = operation match {
          case "PROCESSING" | "SAVING" =>
            // For processing/saving, use processedValid (saved records)
            // Fall back to processedIncrementalValid if processedValid is 0 or not set
            // (processedValid can be incorrectly 0 when files are stored compressed as .xml.zst)
            val valid = dsInfo.getLiteralProp(processedValid).map(_.toInt)
            val incrementalValid = dsInfo.getLiteralProp(processedIncrementalValid).map(_.toInt)
            valid.filter(_ > 0).orElse(incrementalValid).orElse(valid)
          case "ANALYZING" | "HARVESTING" =>
            // For analyzing/harvesting, use datasetRecordCount field
            dsInfo.getLiteralProp(datasetRecordCount).map(_.toInt)
          case _ =>
            // For other operations, try datasetRecordCount
            dsInfo.getLiteralProp(datasetRecordCount).map(_.toInt)
        }

        // Log operation completion if we were tracking one
        completedStartTime.foreach { startTime =>
          // Log operation complete with record count and acquisition counts
          ActivityLogger.logOperationComplete(
            activityLog = datasetContext.activityLog,
            operation = operation,
            trigger = trigger,
            startTime = startTime,
            workflowId = currentWorkflowId,
            recordCount = recordCountOpt,
            acquiredCount = dsInfo.getLiteralProp(acquiredRecordCount).map(_.toInt),
            deletedCount = dsInfo.getLiteralProp(deletedRecordCount).map(_.toInt),
            sourceCount = dsInfo.getLiteralProp(sourceRecordCount).map(_.toInt),
            validCount = dsInfo.getLiteralProp(processedValid).map(_.toInt),
            invalidCount = dsInfo.getLiteralProp(processedInvalid).map(_.toInt),
            acquisitionMethod = dsInfo.getLiteralProp(acquisitionMethod)
          )

          // If this completes a workflow, log workflow completion
          if (currentWorkflowId.isDefined && workflowStartTime.isDefined) {
            ActivityLogger.completeWorkflow(
              datasetContext.activityLog,
              currentWorkflowId.get,
              workflowStartTime.get,
              recordCountOpt  // Use the same record count from the final operation
            )
            // Clear workflow tracking
            currentWorkflowId = None
            workflowStartTime = None
          }
        }

        // Notify parent that this dataset is now idle (include record count for stats)
        context.parent ! DatasetBecameIdle(dsInfo.spec, recordCountOpt)

      } else if (fromState == Idle) {
        // Entering an active state from Idle - this is a new operation
        val operation = toState.toString.toUpperCase
        val trigger = dsInfo.getOperationTrigger.getOrElse("manual")

        // For automatic triggers starting from Idle, create a new workflow
        if (trigger == "automatic") {
          val workflowId = ActivityLogger.generateWorkflowId(dsInfo.spec)
          currentWorkflowId = Some(workflowId)
          workflowStartTime = Some(DateTime.now())

          // Log workflow start with expected operations
          val expectedOps = operation match {
            case "HARVESTING" => Seq("HARVEST", "PROCESS", "SAVE")
            case _ => Seq(operation)
          }
          ActivityLogger.startWorkflow(
            datasetContext.activityLog,
            workflowId,
            "periodic", // Most automatic triggers are periodic
            expectedOps
          )
        }

        // Log operation start
        operationStartTime = Some(DateTime.now())
        ActivityLogger.logOperationStart(
          datasetContext.activityLog,
          operation,
          trigger,
          currentWorkflowId
        )

        // Track in triple store for restart recovery
        dsInfo.setCurrentOperation(operation, trigger)

        // Notify parent that this dataset became active
        context.parent ! DatasetBecameActive(dsInfo.spec)

      } else {
        // Transitioning between active states within a workflow
        val newOperation = toState.toString.toUpperCase
        val trigger = dsInfo.getOperationTrigger.getOrElse("manual")

        // Complete the previous operation
        operationStartTime.foreach { startTime =>
          val oldOperation = fromState.toString.toUpperCase

          // Determine record count for the completed operation
          val recordCountOpt: Option[Int] = oldOperation match {
            case "PROCESSING" | "SAVING" =>
              for {
                valid <- dsInfo.getLiteralProp(processedValid).map(_.toInt)
                invalid <- dsInfo.getLiteralProp(processedInvalid).map(_.toInt)
              } yield valid + invalid
            case "ANALYZING" | "HARVESTING" =>
              dsInfo.getLiteralProp(datasetRecordCount).map(_.toInt)
            case _ =>
              dsInfo.getLiteralProp(datasetRecordCount).map(_.toInt)
          }

          ActivityLogger.logOperationComplete(
            activityLog = datasetContext.activityLog,
            operation = oldOperation,
            trigger = trigger,
            startTime = startTime,
            workflowId = currentWorkflowId,
            recordCount = recordCountOpt,
            acquiredCount = dsInfo.getLiteralProp(acquiredRecordCount).map(_.toInt),
            deletedCount = dsInfo.getLiteralProp(deletedRecordCount).map(_.toInt),
            sourceCount = dsInfo.getLiteralProp(sourceRecordCount).map(_.toInt),
            validCount = dsInfo.getLiteralProp(processedValid).map(_.toInt),
            invalidCount = dsInfo.getLiteralProp(processedInvalid).map(_.toInt),
            acquisitionMethod = dsInfo.getLiteralProp(acquisitionMethod)
          )
        }

        // Start the new operation
        operationStartTime = Some(DateTime.now())
        ActivityLogger.logOperationStart(
          datasetContext.activityLog,
          newOperation,
          trigger,
          currentWorkflowId
        )

        // Update triple store
        dsInfo.setCurrentOperation(newOperation)
      }
  }

}
