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
import mapping.CategoryCounter.CategoryCountComplete
import organization.OrgActor._

import scala.concurrent.ExecutionContext
import scala.language.postfixOps
import scala.util.{Success, Failure}

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
    queued: List[(String, String, Int)]  // (spec, trigger, position)
  )
  case object GetQueueStatus
  case class CancelResult(success: Boolean, message: String)

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

  override def preStart(): Unit = {
    super.preStart()
    // Perform startup recovery for interrupted operations
    performStartupRecovery()
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
      routeToDatasetActor(spec, message)
    } else if (orgContext.semaphore.tryAcquire(spec)) {
      // Got semaphore, execute immediately
      log.info(s"Acquired semaphore for $spec ($trigger), executing immediately. Available: ${orgContext.semaphore.availablePermits()}")
      routeToDatasetActor(spec, message)
    } else {
      // Queue the operation with priority (manual before periodic/recovery)
      val alreadyQueued = operationQueue.exists(_.spec == spec)
      if (!alreadyQueued) {
        val newOp = QueuedOperation(spec, message, trigger)
        operationQueue = insertWithPriority(operationQueue, newOp)
        val position = operationQueue.indexWhere(_.spec == spec) + 1
        log.info(s"Queued $spec ($trigger) at position $position of ${operationQueue.length}")
      } else {
        log.info(s"$spec already queued, ignoring duplicate request")
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
    while (operationQueue.nonEmpty && orgContext.semaphore.availablePermits() > 0) {
      val next = operationQueue.head
      if (orgContext.semaphore.tryAcquire(next.spec)) {
        operationQueue = operationQueue.tail
        log.info(s"Dequeued ${next.spec} (${next.trigger}), ${operationQueue.length} remaining in queue")
        routeToDatasetActor(next.spec, next.message)
      } else {
        // Couldn't acquire (maybe same spec already active), try next
        return
      }
    }
  }

  private def routeToDatasetActor(spec: String, message: AnyRef): Unit = {
    val actor: ActorRef = context.child(spec).getOrElse {
      val datasetContext = orgContext.datasetContext(spec)
      val datasetActor = context.actorOf(props(datasetContext, orgContext.mailService, orgContext, harvestingExecutionContext), spec)
      log.info(s"Created dataset actor $datasetActor")
      context.watch(datasetActor)
      datasetActor
    }
    actor ! message
  }

  private def isOperationMessage(message: AnyRef): Boolean = message match {
    case _: StartHarvest => true
    case _: StartProcessing => true
    case _: StartSaving => true
    case Command(cmd) => cmd.startsWith("start ") && !cmd.contains("refresh")
    case _ => false
  }

  private def removeFromQueue(spec: String): Unit = {
    val wasQueued = operationQueue.exists(_.spec == spec)
    if (wasQueued) {
      operationQueue = operationQueue.filterNot(_.spec == spec)
      log.info(s"Removed $spec from queue")
    }
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
      sender() ! QueueStatus(processing, saving, queued)

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

    case spurious =>
      log.error(s"Spurious message $spurious")

  }
}



