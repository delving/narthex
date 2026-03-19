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

package harvest

import akka.actor.{Actor, Props}
import play.api.Logger
import dataset.DatasetActor.{FromScratch, FromScratchIncremental, ModifiedAfter, StartHarvest}
import organization.OrgActor.EnqueueOperation
import organization.OrgContext
import services.{GlobalDsInfoService, HarvestableDataset}

object PeriodicHarvest {

  case object ScanForHarvests

  def props(orgContext: OrgContext) = Props(new PeriodicHarvest(orgContext))

}

class PeriodicHarvest(orgContext: OrgContext) extends Actor {

  private val logger = Logger(getClass)

  private def dsInfoService = GlobalDsInfoService.get().getOrElse(
    throw new RuntimeException("DsInfoService not initialized")
  )

  def receive = {
    case PeriodicHarvest.ScanForHarvests =>
      scanForScheduledHarvests()
      checkRetryHarvests()
  }

  private def scanForScheduledHarvests(): Unit = {
    val orgId = orgContext.appConfig.orgId
    val retryIntervalMinutes = orgContext.appConfig.harvestRetryIntervalMinutes

    val harvestable = dsInfoService.listHarvestableNow(orgId)
    logger.info(s"PeriodicHarvest: Found ${harvestable.size} datasets with schedules")

    harvestable.foreach { ds =>
      if (ds.activeWorkflowId.isDefined) {
        logger.debug(s"PeriodicHarvest: Skipping ${ds.spec} — workflow ${ds.activeWorkflowId.get} is ${ds.activeWorkflowStatus.get}")
      } else {
        val isTime = isScheduledToRun(ds)
        if (isTime) {
          logger.info(s"PeriodicHarvest: Scheduling ${ds.spec} (incremental=${ds.incremental})")
          val strategy = if (ds.incremental) FromScratchIncremental else FromScratch
          orgContext.orgActor ! EnqueueOperation(ds.spec, StartHarvest(strategy), "periodic")
        } else {
          logger.debug(s"PeriodicHarvest: ${ds.spec} not yet due")
        }
      }
    }
  }

  private def isScheduledToRun(ds: HarvestableDataset): Boolean = {
    val previous = if (ds.incremental) ds.lastIncrementalHarvest else ds.lastFullHarvest
    if (previous.isEmpty) return true

    val delay = ds.delay.flatMap(d => scala.util.Try(d.toInt).toOption).getOrElse(0)
    if (delay <= 0) return true

    val unit = ds.delayUnit.flatMap {
      case "days" | "DAYS" => Some(services.Temporal.DelayUnit.DAYS)
      case "hours" | "HOURS" => Some(services.Temporal.DelayUnit.HOURS)
      case "weeks" | "WEEKS" => Some(services.Temporal.DelayUnit.WEEKS)
      case _ => None
    }.getOrElse(services.Temporal.DelayUnit.DAYS)

    val dueTime = unit.after(
      new org.joda.time.DateTime(previous.get.toEpochMilli),
      delay
    )
    val now = new org.joda.time.DateTime()
    dueTime.isBefore(now) || dueTime.isEqual(now)
  }

  private def checkRetryHarvests(): Unit = {
    val orgId = orgContext.appConfig.orgId
    val retryIntervalMinutes = orgContext.appConfig.harvestRetryIntervalMinutes

    val retryable = dsInfoService.listRetryableNow(orgId, retryIntervalMinutes)
    logger.info(s"PeriodicHarvest: Found ${retryable.size} datasets in retry mode")

    retryable.foreach { ds =>
      if (ds.activeWorkflowId.isEmpty) {
        logger.info(s"PeriodicHarvest: Scheduling retry for ${ds.spec}")
        orgContext.orgActor ! EnqueueOperation(ds.spec, StartHarvest(FromScratch), "periodic")
      } else {
        logger.debug(s"PeriodicHarvest: Skipping retry for ${ds.spec} — active workflow ${ds.activeWorkflowId.get}")
      }
    }
  }
}
