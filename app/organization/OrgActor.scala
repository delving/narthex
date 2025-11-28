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

}

class OrgActor (
  orgContext: OrgContext,
  actorSystem: ActorSystem
) extends Actor with ActorLogging {

  val harvestingExecutionContext = actorSystem.dispatchers.lookup("contexts.dataset-harvesting-execution-context")

  var results = Map.empty[String, Option[List[CategoryCount]]]

  // Track which datasets are currently active (not in Idle state)
  var activeDatasets = Set.empty[String]

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

            // Hybrid recovery strategy
            (operation, stale) match {
              case ("SAVING", false) =>
                // Auto-resume recent SAVING operations (safe, idempotent)
                log.info(s"Auto-resuming SAVING operation for ${dsInfo.spec}")
                self ! DatasetMessage(dsInfo.spec, StartSaving(None))

              case ("PROCESSING", false) =>
                // Auto-resume recent PROCESSING operations
                log.info(s"Auto-resuming PROCESSING operation for ${dsInfo.spec}")
                self ! DatasetMessage(dsInfo.spec, StartProcessing(None))

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

  def receive = {

    case DatasetMessage(spec, message) =>
      val actor: ActorRef = context.child(spec).getOrElse {
        val datasetContext = orgContext.datasetContext(spec)
        val datasetActor = context.actorOf(props(datasetContext, orgContext.mailService, orgContext, harvestingExecutionContext), spec)
        log.info(s"Created dataset actor $datasetActor")
        context.watch(datasetActor)
        datasetActor
      }
      actor ! message

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

    case GetActiveDatasets =>
      // Simply return the tracked set of active datasets
      sender() ! ActiveDatasets(activeDatasets.toList.sorted)

    case Terminated(name) =>
      log.info(s"Demised $name")
      log.info(s"Children: ${context.children}")

    case spurious =>
      log.error(s"Spurious message $spurious")

  }
}



