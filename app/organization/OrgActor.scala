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
import record.SourceProcessor.AdoptSource
import organization.OrgActor._
import play.api.libs.json._


import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Success, Failure}

/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

object OrgActor {

  case class DatasetMessage(spec: String, message: AnyRef)

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
    durationSeconds: Option[Long],
    recordCount: Option[Int] = None
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
    durationSeconds: Option[Long],
    recordCount: Option[Int] = None
  )

  // JSON format for CompletionDetail (used in API responses)
  implicit val completionDetailFormat: Format[CompletionDetail] = Json.format[CompletionDetail]

  // Queue health check messages
  case object ProcessQueueOnStartup
  case object PeriodicQueueCheck

}

class OrgActor (
  orgContext: OrgContext,
  actorSystem: ActorSystem
) extends Actor with ActorLogging {

  val harvestingExecutionContext = actorSystem.dispatchers.lookup("contexts.dataset-harvesting-execution-context")

  // Track which datasets are currently active (not in Idle state)
  var activeDatasets = Set.empty[String]

  // Pending operations live in the persistent job queue (queue.db, Phase
  // A3b-1): queued work survives restarts. Semantics unchanged — semaphores
  // still guard execution; this is only the waiting line.
  private def jobQueue = orgContext.jobQueue

  // Track completed operations for observability stats
  var completedOperations: Vector[CompletedOperation] = Vector.empty

  // Track specs with active workflows (spec -> (trigger, startTimeMillis)) for completion tracking
  // This tracks the trigger and start time from when a workflow starts (harvest/process/save)
  // so we can attribute completions correctly even for multi-step workflows
  var workflowSpecs: Map[String, (String, Long)] = Map.empty

  // Scheduler for periodic queue health check
  var queueCheckScheduler: Option[Cancellable] = None

  override def preStart(): Unit = {
    super.preStart()

    // Leases from a previous JVM are meaningless (that work died with it);
    // startup recovery below re-enqueues what should resume.
    val cleared = orgContext.jobQueue.clearLeases()
    if (cleared > 0) log.warning(s"Cleared $cleared stale lease(s) from previous run")

    // Schedule periodic queue health check every 30 seconds
    // This ensures queued items get processed even if semaphore release messages are lost
    implicit val ec: ExecutionContext = context.dispatcher
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
    // Cancel the queue check scheduler
    queueCheckScheduler.foreach(_.cancel())

    super.postStop()
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
      case StartHarvest(Sample, _) => true
      case Command(cmd) if cmd == "start sample harvest" => true
      case _ => false
    }

    if (!needsSemaphore || isSampleHarvest) {
      // Non-processing commands and sample harvests execute immediately
      routeToDatasetActor(spec, message, trigger)
    } else {
      val payloadOpt = services.JobPayload.encode(message)
      payloadOpt match {
        case None =>
          // Every operation message has a codec; reaching this is a bug.
          log.error(s"Cannot lease/queue unserializable message for $spec: $message — dropping")
        case Some(payload) =>
          if (jobQueue.tryLease(spec, payload, trigger, concurrencyLimit)) {
            log.info(s"Leased $spec ($trigger), executing immediately. Leased: ${jobQueue.leasedCount()}/$concurrencyLimit")
            routeToDatasetActor(spec, message, trigger)
          } else if (jobQueue.isLeased(spec)) {
            // Dataset is already processing - don't queue another operation
            log.warning(s"$spec is already active (leased), ignoring $trigger request")
          } else if (jobQueue.enqueue(spec, payload, trigger)) {
            log.info(s"Queued $spec ($trigger); queue size ${jobQueue.size()}")
          } else {
            log.info(s"$spec already queued, ignoring duplicate request")
          }
      }
    }
  }

  private def processQueue(): Unit = {
    // Promote queued jobs to leased in priority order; specs already leased
    // stay queued for a later pass.
    var processed = 0
    jobQueue.queued().foreach { job =>
      if (jobQueue.leaseQueued(job.jobId, job.spec, concurrencyLimit)) {
        services.JobPayload.decode(job.payload, orgContext) match {
          case Some(message) =>
            processed += 1
            log.info(s"Dequeued ${job.spec} (${job.trigger}), starting execution")
            routeToDatasetActor(job.spec, message, job.trigger)
          case None =>
            // Undecodable row (e.g. from a future/older version): drop it
            // loudly rather than wedging the queue.
            jobQueue.releaseLease(job.spec)
            log.error(s"Dropped undecodable queued job for ${job.spec}: ${job.payload}")
        }
      }
    }

    if (processed > 0) {
      log.info(s"Processed $processed items from queue, ${jobQueue.size()} remaining")
    }
  }

  private def concurrencyLimit: Int = orgContext.narthexConfig.concurrencyLimit

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
      // Analysis is a quick local operation
      val heavyCommands = Set(
        "start sample harvest",
        "start first harvest",
        "start first harvest with auto-process",
        "start generating sip",
        "start processing",
        "start saving"
      )
      val isFastSave = cmd.startsWith("start fast save") || cmd.startsWith("start fast process")
      heavyCommands.contains(cmd) || isFastSave
    case _ => false
  }

  private def removeFromQueue(spec: String): Unit = {
    if (jobQueue.removeSpec(spec)) log.info(s"Removed $spec from queue")
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

    case DatasetBecameActive(spec) =>
      log.info(s"Dataset $spec became active")
      activeDatasets = activeDatasets + spec

    case DatasetBecameIdle(spec, recordCount) =>
      // D3: completion stats get real record volumes — callers pass None,
      // so read the just-finished run (sent if it saved, else seen).
      val effectiveCount = recordCount.orElse {
        scala.util.Try(orgContext.recordRegistry.listRuns(spec, 30)).toOption
          .flatMap(_.filter(_.status == "completed").sortBy(_.runId).lastOption)
          .map(r => if (r.sent > 0) r.sent else r.seen)
      }
      log.info(s"Dataset $spec became idle (records: ${effectiveCount.getOrElse("N/A")})")
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
            durationSeconds = Some(durationSeconds),
            recordCount = effectiveCount
          )
          completedOperations = completedOperations :+ completed
          workflowSpecs = workflowSpecs - spec  // Remove from tracking
          log.info(s"Tracked workflow completion for $spec (trigger: $trigger, duration: ${durationSeconds}s, records: ${recordCount.getOrElse("N/A")})")

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

      if (jobQueue.releaseLease(spec)) log.info(s"Released lease for $spec")
      removeFromQueue(spec)  // Remove if still in queue (e.g., cancelled)
      // The dataset's own completion broadcast ran while the lease was still
      // held (phase=running); push a fresh status document now that the
      // lease is gone so the row lands on the true idle phase/actions.
      sender() ! BroadcastStatus
      processQueue()         // Try to start next queued operation

    case GetActiveDatasets =>
      // Simply return the tracked set of active datasets
      sender() ! ActiveDatasets(activeDatasets.toList.sorted)

    case GetQueueStatus =>
      val processing = jobQueue.leasedSpecs().toList
      val saving = List.empty[String]   // merged into the single lease
      val queued = jobQueue.queued().zipWithIndex.map { case (job, idx) =>
        (job.spec, job.trigger, idx + 1)
      }.toList
      val stats = calculateCompletionStats()
      // Convert CompletedOperation to CompletionDetail for frontend
      val details = completedOperations.map { op =>
        CompletionDetail(op.spec, op.completedAt, op.trigger, op.durationSeconds, op.recordCount)
      }.toList
      sender() ! QueueStatus(processing, saving, queued, stats, details)

    case CancelQueuedOperation(spec) =>
      if (jobQueue.removeSpec(spec)) {
        sender() ! CancelResult(true, s"Removed $spec from queue")
      } else {
        sender() ! CancelResult(false, s"$spec was not in queue")
      }

    case Terminated(ref) =>
      log.info(s"Demised $ref")
      val spec = ref.path.name
      if (jobQueue.releaseLease(spec)) {
        log.warning(s"Released lease for terminated dataset actor $spec")
        processQueue()
      }

    case ProcessQueueOnStartup =>
      // Rows queued before a restart are still here — drain them.
      log.info(s"Processing queue on startup: ${jobQueue.size()} items queued, ${jobQueue.leasedCount()}/$concurrencyLimit leased")
      processQueue()

    case PeriodicQueueCheck =>
      // Safety net: reclaim leases whose completion signal was lost (the
      // dataset is no longer active but the lease is old), then drain.
      val reclaimed = jobQueue.reclaimStaleLeases(
        orgContext.narthexConfig.leaseTimeoutMinutes, activeDatasets)
      reclaimed.foreach(spec => log.warning(s"Reclaimed stale lease for $spec (no active work)"))
      if (jobQueue.size() > 0 && jobQueue.leasedCount() < concurrencyLimit) {
        log.info(s"Periodic queue check: ${jobQueue.size()} queued, ${jobQueue.leasedCount()}/$concurrencyLimit leased - processing")
        processQueue()
      }

    case spurious =>
      log.error(s"Spurious message $spurious")

  }
}



