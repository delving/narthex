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

package organization

import akka.actor._
import dataset.DatasetActor._
import mapping.CategoriesSpreadsheet.CategoryCount
import record.SourceProcessor.AdoptSource
import mapping.CategoryCounter.CategoryCountComplete
import organization.OrgActor._
import play.api.libs.json._

import java.io.{File, PrintWriter}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.io.Source
import scala.language.postfixOps
import scala.util.{Success, Failure, Try}

/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

object OrgActor {

  case class DatasetMessage(spec: String, message: AnyRef)

  case class DatasetsCountCategories(datasets: Seq[String])

  case object GetActiveDatasets

  case class ActiveDatasets(specs: List[String])

  // Queue system for enforcing global concurrency limit
  case class QueuedOperation(
    spec: String,
    message: AnyRef,
    trigger: String,  // "manual", "periodic", "recovery"
    enqueuedAt: Long = System.currentTimeMillis()
  )

  case class EnqueueOperation(spec: String, message: AnyRef, trigger: String)
  case class CancelQueuedOperation(spec: String)
  case class QueueStatus(
    processing: List[String],
    saving: List[String],
    queued: List[(String, String, Int)],  // (spec, trigger, position)
    stats: CompletionStats,
    completionDetails: List[CompletionDetail]  // Raw operation details for 24h
  )
  case object GetQueueStatus
  case class CancelResult(success: Boolean, message: String)

  // Completion tracking for observability
  case class CompletedOperation(
    spec: String,
    completedAt: Long,
    trigger: String,           // "manual", "periodic", "recovery"
    durationSeconds: Option[Long]
  )

  case class CompletionStats(
    manual1h: Int, automatic1h: Int,
    manual4h: Int, automatic4h: Int,
    manual24h: Int, automatic24h: Int
  )

  // Detail record for individual completed operations (exposed to frontend)
  case class CompletionDetail(
    spec: String,
    completedAt: Long,
    trigger: String,
    durationSeconds: Option[Long]
  )

  // Queue persistence and health checks
  case object PersistQueueState
  case object ShutdownPersist
  case object ProcessQueueOnStartup
  case object PeriodicQueueCheck

  // Persisted queue entry (serializable version of QueuedOperation)
  case class PersistedQueueEntry(
    spec: String,
    messageType: String,    // "StartHarvest", "StartProcessing", "StartSaving", "Command"
    messageArg: String,     // harvest strategy or command string
    trigger: String,
    enqueuedAt: Long
  )

  case class PersistedQueueState(
    activeSpecs: List[String],      // Were actively processing (need priority re-queue)
    queuedEntries: List[PersistedQueueEntry],  // Were waiting in queue
    completedOperations: List[CompletedOperation],  // Completed operations for stats
    savedAt: Long
  )

  // JSON format for persistence
  implicit val completedOperationFormat: Format[CompletedOperation] = Json.format[CompletedOperation]
  implicit val completionStatsFormat: Format[CompletionStats] = Json.format[CompletionStats]
  implicit val completionDetailFormat: Format[CompletionDetail] = Json.format[CompletionDetail]
  implicit val persistedQueueEntryFormat: Format[PersistedQueueEntry] = Json.format[PersistedQueueEntry]
  implicit val persistedQueueStateFormat: Format[PersistedQueueState] = Json.format[PersistedQueueState]

  // Helper to serialize message to persistable form
  def messageToPersistedEntry(spec: String, message: AnyRef, trigger: String, enqueuedAt: Long): Option[PersistedQueueEntry] = {
    message match {
      case StartHarvest(strategy) =>
        val strategyStr = strategy match {
          case Sample => "Sample"
          case FromScratch => "FromScratch"
          case FromScratchIncremental => "FromScratchIncremental"
          case ModifiedAfter(_, _) => "FromScratchIncremental"  // Treat as incremental on restore
        }
        Some(PersistedQueueEntry(spec, "StartHarvest", strategyStr, trigger, enqueuedAt))
      case StartProcessing(_) =>
        Some(PersistedQueueEntry(spec, "StartProcessing", "", trigger, enqueuedAt))
      case StartSaving(_) =>
        Some(PersistedQueueEntry(spec, "StartSaving", "", trigger, enqueuedAt))
      case Command(cmd) =>
        Some(PersistedQueueEntry(spec, "Command", cmd, trigger, enqueuedAt))
      case _ =>
        None  // Don't persist other message types
    }
  }

  // Helper to deserialize persisted entry back to message
  def persistedEntryToMessage(entry: PersistedQueueEntry): Option[AnyRef] = {
    entry.messageType match {
      case "StartHarvest" =>
        val strategy = entry.messageArg match {
          case "Sample" => Sample
          case "FromScratch" => FromScratch
          case _ => FromScratchIncremental
        }
        Some(StartHarvest(strategy))
      case "StartProcessing" =>
        Some(StartProcessing(None))
      case "StartSaving" =>
        Some(StartSaving(None))
      case "Command" =>
        Some(Command(entry.messageArg))
      case _ =>
        None
    }
  }

}

class OrgActor (
  orgContext: OrgContext,
  actorSystem: ActorSystem
) extends Actor with ActorLogging {

  val harvestingExecutionContext = actorSystem.dispatchers.lookup("contexts.dataset-harvesting-execution-context")

  var results = Map.empty[String, Option[List[CategoryCount]]]

  // Track which datasets are currently active (not in Idle state)
  var activeDatasets = Set.empty[String]

  // Queue for pending operations when concurrency limit reached
  // Priority queue: manual operations come before periodic/recovery
  var operationQueue: Vector[QueuedOperation] = Vector.empty

  // Track completed operations for observability stats
  var completedOperations: Vector[CompletedOperation] = Vector.empty

  // Track specs with active workflows (spec -> (trigger, startTimeMillis)) for completion tracking
  // This tracks the trigger and start time from when a workflow starts (harvest/process/save)
  // so we can attribute completions correctly even for multi-step workflows
  var workflowSpecs: Map[String, (String, Long)] = Map.empty

  // Queue persistence file
  val queueStateFile = new File(orgContext.orgRoot, "queue-state.json")

  // Schedulers for periodic tasks
  var persistScheduler: Option[Cancellable] = None
  var queueCheckScheduler: Option[Cancellable] = None

  override def preStart(): Unit = {
    super.preStart()

    // Load persisted queue state first (before startup recovery)
    loadPersistedQueueState()

    // Schedule periodic queue persistence every minute
    implicit val ec: ExecutionContext = context.dispatcher
    persistScheduler = Some(
      context.system.scheduler.scheduleWithFixedDelay(
        1.minute, 1.minute, self, PersistQueueState
      )
    )
    log.info("Queue persistence scheduler started (every 1 minute)")

    // Schedule periodic queue health check every 30 seconds
    // This ensures queued items get processed even if semaphore release messages are lost
    queueCheckScheduler = Some(
      context.system.scheduler.scheduleWithFixedDelay(
        30.seconds, 30.seconds, self, PeriodicQueueCheck
      )
    )
    log.info("Queue health check scheduler started (every 30 seconds)")

    // Perform startup recovery for interrupted operations
    performStartupRecovery()

    // Schedule queue processing after a short delay to start any restored queued items
    // This ensures the queue is processed even when no workers were active before restart
    context.system.scheduler.scheduleOnce(5.seconds, self, ProcessQueueOnStartup)
    log.info("Scheduled queue processing for 5 seconds after startup")
  }

  override def postStop(): Unit = {
    // Cancel the schedulers
    persistScheduler.foreach(_.cancel())
    queueCheckScheduler.foreach(_.cancel())

    // Final save on shutdown
    saveQueueState()
    log.info("Queue state persisted on shutdown")

    super.postStop()
  }

  // Save current queue state to file
  private def saveQueueState(): Unit = {
    import scala.jdk.CollectionConverters._
    Try {
      // Get currently active specs from semaphore
      val activeSpecs = orgContext.semaphore.activeSpecs().asScala.toList

      // Convert queued operations to persisted entries
      val queuedEntries = operationQueue.flatMap { op =>
        messageToPersistedEntry(op.spec, op.message, op.trigger, op.enqueuedAt)
      }.toList

      val state = PersistedQueueState(
        activeSpecs = activeSpecs,
        queuedEntries = queuedEntries,
        completedOperations = completedOperations.toList,
        savedAt = System.currentTimeMillis()
      )

      val json = Json.prettyPrint(Json.toJson(state))
      val writer = new PrintWriter(queueStateFile)
      try {
        writer.write(json)
        log.debug(s"Queue state saved: ${activeSpecs.length} active, ${queuedEntries.length} queued")
      } finally {
        writer.close()
      }
    }.recover {
      case e: Exception =>
        log.error(e, "Failed to save queue state")
    }
  }

  // Load persisted queue state and re-queue operations
  private def loadPersistedQueueState(): Unit = {
    if (!queueStateFile.exists()) {
      log.info("No persisted queue state found - clean start")
      return
    }

    Try {
      val source = Source.fromFile(queueStateFile)
      try {
        val json = source.mkString
        Json.parse(json).validate[PersistedQueueState] match {
          case JsSuccess(state, _) =>
            val age = System.currentTimeMillis() - state.savedAt
            val ageMinutes = age / 60000

            log.info(s"Loading persisted queue state (saved ${ageMinutes} minutes ago)")
            log.info(s"  Active specs to restore: ${state.activeSpecs.mkString(", ")}")
            log.info(s"  Queued entries to restore: ${state.queuedEntries.length}")

            // Re-queue previously active specs first (they were interrupted)
            // Mark them as "recovery" trigger since they need to resume
            state.activeSpecs.foreach { spec =>
              // We don't know what operation was running, so we'll let the
              // existing startup recovery handle this based on triple store state
              log.info(s"Previously active spec $spec will be handled by startup recovery")
            }

            // Re-queue the previously queued entries (skip duplicates)
            state.queuedEntries.foreach { entry =>
              val alreadyQueued = operationQueue.exists(_.spec == entry.spec)
              if (alreadyQueued) {
                log.info(s"Skipping duplicate queued entry for ${entry.spec}")
              } else {
                persistedEntryToMessage(entry) match {
                  case Some(message) =>
                    log.info(s"Restoring queued operation: ${entry.spec} (${entry.messageType}, ${entry.trigger})")
                    // Use enqueuedAt from persisted entry to maintain order
                    val op = QueuedOperation(entry.spec, message, entry.trigger, entry.enqueuedAt)
                    operationQueue = insertWithPriority(operationQueue, op)
                  case None =>
                    log.warning(s"Could not restore queued operation for ${entry.spec}: unknown message type ${entry.messageType}")
                }
              }
            }

            if (operationQueue.nonEmpty) {
              log.info(s"Restored ${operationQueue.length} queued operations")
            }

            // Restore completed operations (prune entries older than 24h)
            val cutoff = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
            completedOperations = state.completedOperations.filter(_.completedAt > cutoff).toVector
            if (completedOperations.nonEmpty) {
              log.info(s"Restored ${completedOperations.length} completed operation entries")
            }

          case JsError(errors) =>
            log.warning(s"Failed to parse persisted queue state: $errors")
        }
      } finally {
        source.close()
      }

      // Delete the file after loading (will be recreated on next save)
      queueStateFile.delete()
      log.info("Deleted persisted queue state file after loading")

    }.recover {
      case e: Exception =>
        log.error(e, "Failed to load persisted queue state")
    }
  }

  def performStartupRecovery(): Unit = {
    import scala.concurrent.duration._
    import dataset.DsInfo.listDsInfoWithIncompleteOperations
    import dataset.DatasetActor.{StartSaving, StartProcessing}
    implicit val ec: ExecutionContext = context.dispatcher
    implicit val ts: triplestore.TripleStore = orgContext.ts

    log.info("Checking for incomplete operations from previous session...")

    listDsInfoWithIncompleteOperations(orgContext).onComplete {
      case Success(incompleteList) =>
        if (incompleteList.nonEmpty) {
          log.info(s"Found ${incompleteList.length} datasets with incomplete operations")

          incompleteList.foreach { dsInfo =>
            val operation = dsInfo.getCurrentOperation.getOrElse("UNKNOWN")
            val trigger = dsInfo.getOperationTrigger.getOrElse("unknown")
            val stale = dsInfo.isOperationStale(30) // 30 minutes threshold

            log.info(s"Dataset ${dsInfo.spec}: operation=$operation, trigger=$trigger, stale=$stale")

            // Hybrid recovery strategy - use queue to respect concurrency limits
            (operation, stale) match {
              case ("SAVING", false) =>
                // Queue SAVING operation for recovery (respects concurrency limit)
                log.info(s"Queuing SAVING operation for ${dsInfo.spec} (recovery)")
                self ! EnqueueOperation(dsInfo.spec, StartSaving(None), "recovery")

              case ("PROCESSING", false) =>
                // Queue PROCESSING operation for recovery (respects concurrency limit)
                log.info(s"Queuing PROCESSING operation for ${dsInfo.spec} (recovery)")
                self ! EnqueueOperation(dsInfo.spec, StartProcessing(None), "recovery")

              case _ =>
                // Flag stale or risky operations for manual review
                log.warning(s"Dataset ${dsInfo.spec} has stale/risky operation $operation (started ${dsInfo.getOperationStartTime})")
                log.warning(s"Setting error state to flag for manual review")
                dsInfo.setError(s"Operation '$operation' interrupted during restart. Manual review recommended. Trigger: $trigger")
                dsInfo.clearOperation()
            }
          }
        } else {
          log.info("No incomplete operations found - clean startup")
        }

      case Failure(e) =>
        log.error(e, "Error checking for incomplete operations during startup")
    }
  }

  // Queue processing methods

  private def enqueueOrExecute(spec: String, message: AnyRef, trigger: String): Unit = {
    // Check if this is an operation that needs semaphore (harvest, process, save commands)
    val needsSemaphore = isOperationMessage(message)

    // Sample harvests bypass the queue entirely (for testing purposes)
    val isSampleHarvest = message match {
      case StartHarvest(Sample) => true
      case Command(cmd) if cmd == "start sample harvest" => true
      case _ => false
    }

    if (!needsSemaphore || isSampleHarvest) {
      // Non-processing commands and sample harvests execute immediately
      routeToDatasetActor(spec, message, trigger)
    } else if (orgContext.semaphore.tryAcquire(spec)) {
      // Got semaphore, execute immediately
      log.info(s"Acquired semaphore for $spec ($trigger), executing immediately. Available: ${orgContext.semaphore.availablePermits()}")
      routeToDatasetActor(spec, message, trigger)
    } else {
      // Couldn't acquire semaphore - check why
      val isAlreadyActive = orgContext.semaphore.isActive(spec)
      val alreadyQueued = operationQueue.exists(_.spec == spec)

      if (isAlreadyActive) {
        // Dataset is already processing - don't queue another operation
        log.warning(s"$spec is already active (processing/saving), ignoring $trigger request")
      } else if (alreadyQueued) {
        log.info(s"$spec already queued, ignoring duplicate request")
      } else {
        // Not active and not queued - queue it (all workers busy)
        val newOp = QueuedOperation(spec, message, trigger)
        operationQueue = insertWithPriority(operationQueue, newOp)
        val position = operationQueue.indexWhere(_.spec == spec) + 1
        log.info(s"Queued $spec ($trigger) at position $position of ${operationQueue.length}")
      }
    }
  }

  // Insert with priority: manual operations come before periodic/recovery
  private def insertWithPriority(queue: Vector[QueuedOperation], op: QueuedOperation): Vector[QueuedOperation] = {
    if (op.trigger == "manual") {
      // Find the last manual operation position, insert after it
      val lastManualIdx = queue.lastIndexWhere(_.trigger == "manual")
      if (lastManualIdx >= 0) {
        val (before, after) = queue.splitAt(lastManualIdx + 1)
        (before :+ op) ++ after
      } else {
        // No manual ops, insert at front
        op +: queue
      }
    } else {
      // Non-manual operations go to the end
      queue :+ op
    }
  }

  private def processQueue(): Unit = {
    // Try to dequeue and execute pending operations
    // Skip items whose spec is already active and try the next one
    var remainingQueue = operationQueue
    var newQueue = Vector.empty[QueuedOperation]
    var processed = 0

    while (remainingQueue.nonEmpty && orgContext.semaphore.availablePermits() > 0) {
      val op = remainingQueue.head
      remainingQueue = remainingQueue.tail

      if (orgContext.semaphore.tryAcquire(op.spec)) {
        // Got semaphore, execute this operation
        processed += 1
        log.info(s"Dequeued ${op.spec} (${op.trigger}), starting execution")
        routeToDatasetActor(op.spec, op.message, op.trigger)
      } else {
        // Couldn't acquire (spec already active), keep in queue for later
        newQueue = newQueue :+ op
      }
    }

    // Combine: items we couldn't process + items we didn't reach
    operationQueue = newQueue ++ remainingQueue

    if (processed > 0) {
      log.info(s"Processed $processed items from queue, ${operationQueue.length} remaining")
    }
  }

  private def routeToDatasetActor(spec: String, message: AnyRef, trigger: String = "manual"): Unit = {
    val actor: ActorRef = context.child(spec).getOrElse {
      val datasetContext = orgContext.datasetContext(spec)
      val datasetActor = context.actorOf(props(datasetContext, orgContext.mailService, orgContext, harvestingExecutionContext), spec)
      log.info(s"Created dataset actor $datasetActor")
      context.watch(datasetActor)
      datasetActor
    }

    // Track workflow operations for completion statistics
    // Track any workflow-starting operation (harvest, process, save) so we can
    // attribute the completion to the correct trigger even for multi-step workflows
    if (isWorkflowStartOperation(message)) {
      // Only set if not already tracking (don't overwrite trigger from earlier step)
      if (!workflowSpecs.contains(spec)) {
        val startTime = System.currentTimeMillis()
        workflowSpecs = workflowSpecs + (spec -> (trigger, startTime))
        log.info(s"Tracking workflow for $spec (trigger: $trigger, startTime: $startTime)")
      }
    }

    actor ! message
  }

  private def isWorkflowStartOperation(message: AnyRef): Boolean = message match {
    case _: StartHarvest => true
    case _: StartProcessing => true
    case _: StartSaving => true
    case Command(cmd) => cmd.startsWith("start ") && !cmd.contains("refresh")
    case _ => false
  }

  private def isOperationMessage(message: AnyRef): Boolean = message match {
    case _: StartHarvest => true
    case _: StartProcessing => true
    case _: StartSaving => true
    case _: AdoptSource => true  // File uploads need semaphore control
    case Command(cmd) =>
      // Only heavy operations need semaphore control
      // Analysis, skosification, and category counting are quick local operations
      val heavyCommands = Set(
        "start sample harvest",
        "start first harvest",
        "start generating sip",
        "start processing",
        "start saving"
      )
      val isFastSave = cmd.startsWith("start fast save")
      heavyCommands.contains(cmd) || isFastSave
    case _ => false
  }

  private def removeFromQueue(spec: String): Unit = {
    val wasQueued = operationQueue.exists(_.spec == spec)
    if (wasQueued) {
      operationQueue = operationQueue.filterNot(_.spec == spec)
      log.info(s"Removed $spec from queue")
    }
  }

  private def calculateCompletionStats(): CompletionStats = {
    val now = System.currentTimeMillis()
    val hour1 = now - (1 * 60 * 60 * 1000)
    val hour4 = now - (4 * 60 * 60 * 1000)
    val hour24 = now - (24 * 60 * 60 * 1000)

    def countByTrigger(cutoff: Long, isManual: Boolean): Int = {
      completedOperations.count { op =>
        op.completedAt > cutoff &&
        (if (isManual) op.trigger == "manual" else op.trigger != "manual")
      }
    }

    CompletionStats(
      manual1h = countByTrigger(hour1, isManual = true),
      automatic1h = countByTrigger(hour1, isManual = false),
      manual4h = countByTrigger(hour4, isManual = true),
      automatic4h = countByTrigger(hour4, isManual = false),
      manual24h = countByTrigger(hour24, isManual = true),
      automatic24h = countByTrigger(hour24, isManual = false)
    )
  }

  def receive = {

    case DatasetMessage(spec, message) =>
      // All dataset messages from UI/controllers go through the queue as "manual"
      enqueueOrExecute(spec, message, "manual")

    case EnqueueOperation(spec, message, trigger) =>
      // Explicit queue request with specific trigger type
      enqueueOrExecute(spec, message, trigger)

    case DatasetsCountCategories(datasets) =>
      results = datasets.map(name => (name, None)).toMap
      datasets.foreach(name => self ! DatasetMessage(name, StartCategoryCounting))

    case CategoryCountComplete(dataset, categoryCounts) =>
      results += dataset -> Some(categoryCounts)
      log.info(s"Category counting complete, counts: $results")
      val finishedCountLists = results.values.flatten.toList
      if (finishedCountLists.size == results.size) {
        orgContext.categoriesRepo.createSheet(finishedCountLists.flatten)
        results = Map.empty[String, Option[List[CategoryCount]]]
      }

    case DatasetBecameActive(spec) =>
      log.info(s"Dataset $spec became active")
      activeDatasets = activeDatasets + spec

    case DatasetBecameIdle(spec) =>
      log.info(s"Dataset $spec became idle")
      activeDatasets = activeDatasets - spec

      // Track completion if we were tracking a workflow for this spec
      workflowSpecs.get(spec) match {
        case Some((trigger, startTime)) =>
          val now = System.currentTimeMillis()
          val durationSeconds = (now - startTime) / 1000
          val completed = CompletedOperation(
            spec = spec,
            completedAt = now,
            trigger = trigger,
            durationSeconds = Some(durationSeconds)
          )
          completedOperations = completedOperations :+ completed
          workflowSpecs = workflowSpecs - spec  // Remove from tracking
          log.info(s"Tracked workflow completion for $spec (trigger: $trigger, duration: ${durationSeconds}s)")

          // Prune old entries (older than 24h) and enforce max limit of 3000 entries
          val cutoff = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
          val beforePrune = completedOperations.length
          completedOperations = completedOperations.filter(_.completedAt > cutoff)

          // If still over limit, keep only the most recent 3000
          val maxCompletedEntries = 3000
          if (completedOperations.length > maxCompletedEntries) {
            completedOperations = completedOperations.takeRight(maxCompletedEntries)
          }

          if (completedOperations.length < beforePrune) {
            log.info(s"Pruned ${beforePrune - completedOperations.length} old/excess completion entries")
          }

        case None =>
          // Not a save operation, nothing to track
      }

      removeFromQueue(spec)  // Remove if still in queue (e.g., cancelled)
      processQueue()         // Try to start next queued operation

    case GetActiveDatasets =>
      // Simply return the tracked set of active datasets
      sender() ! ActiveDatasets(activeDatasets.toList.sorted)

    case GetQueueStatus =>
      import scala.jdk.CollectionConverters._
      val processing = orgContext.semaphore.activeSpecs().asScala.toList
      val saving = orgContext.saveSemaphore.activeSpecs().asScala.toList
      val queued = operationQueue.zipWithIndex.map { case (op, idx) =>
        (op.spec, op.trigger, idx + 1)
      }.toList
      val stats = calculateCompletionStats()
      // Convert CompletedOperation to CompletionDetail for frontend
      val details = completedOperations.map { op =>
        CompletionDetail(op.spec, op.completedAt, op.trigger, op.durationSeconds)
      }.toList
      sender() ! QueueStatus(processing, saving, queued, stats, details)

    case CancelQueuedOperation(spec) =>
      val wasQueued = operationQueue.exists(_.spec == spec)
      if (wasQueued) {
        removeFromQueue(spec)
        sender() ! CancelResult(true, s"Removed $spec from queue")
      } else {
        sender() ! CancelResult(false, s"$spec was not in queue")
      }

    case Terminated(name) =>
      log.info(s"Demised $name")
      log.info(s"Children: ${context.children}")

    case PersistQueueState =>
      saveQueueState()

    case ProcessQueueOnStartup =>
      log.info(s"Processing queue on startup: ${operationQueue.length} items queued, ${orgContext.semaphore.availablePermits()} permits available")
      processQueue()

    case PeriodicQueueCheck =>
      // Safety net: periodically try to process queue in case semaphore releases were missed
      if (operationQueue.nonEmpty && orgContext.semaphore.availablePermits() > 0) {
        log.info(s"Periodic queue check: ${operationQueue.length} queued, ${orgContext.semaphore.availablePermits()} permits available - processing")
        processQueue()
      }

    case ShutdownPersist =>
      saveQueueState()
      log.info("Queue state persisted on explicit shutdown request")

    case spurious =>
      log.error(s"Spurious message $spurious")

  }
}



