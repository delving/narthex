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

import scala.util.{Failure, Success}
import scala.language.postfixOps
import play.api.Logger
import akka.actor.{Actor, Props}

import dataset.DatasetActor.{FromScratchIncremental, ModifiedAfter, StartHarvest}
import dataset.DsInfo
import dataset.DsInfo.{DsState, withDsInfo}
import harvest.PeriodicHarvest.ScanForHarvests
import organization.OrgContext
import services.Temporal.DelayUnit
import triplestore.TripleStore

object PeriodicHarvest {

  case object ScanForHarvests

  def props(orgContext: OrgContext) = Props(new PeriodicHarvest(orgContext))

  val harvestingAllowed = List(DsState.SAVED, DsState.INCREMENTAL_SAVED)
}

class PeriodicHarvest(orgContext: OrgContext) extends Actor {

  private val logger = Logger(getClass)

  import context.dispatcher
  implicit val ts: TripleStore = orgContext.ts


  def receive = {

    case ScanForHarvests =>
      // OPTIMIZATION: Only query datasets with harvestable states to prevent error dataset timestamp updates
      val allowedStateStrings = PeriodicHarvest.harvestingAllowed.map(_.toString)
      val futureList = DsInfo.listDsInfoWithStateFilter(orgContext, allowedStateStrings)
      futureList.onComplete {
        case Success(list) =>
          list.
            filter(info => info.hasPreviousTime()). // Only need to filter by previousTime now
            sortWith((s, t) => s.getPreviousHarvestTime().isBefore(t.getPreviousHarvestTime())).
            foreach { listedInfo =>
              val harvestCron = listedInfo.currentHarvestCron
              logger.info(s"scheduled ds: ${listedInfo.spec} ${listedInfo.currentHarvestCron.previous.toString()} (time to work: ${harvestCron.timeToWork})")
              if (harvestCron.timeToWork) withDsInfo(listedInfo.spec, orgContext) { info => // the cached version
                if (orgContext.semaphore.tryAcquire(info.spec)) {
                  logger.info(s"Time to work on $info: $harvestCron")
                  val proposedNext = harvestCron.next
                  val next = if (proposedNext.timeToWork) {
                    val revised = harvestCron.now
                    logger.info(s"$info next harvest $proposedNext is already due so adjusting to 'now': $revised")
                    revised
                  }
                  else {
                    logger.info(s"$info next harvest : $proposedNext")
                    proposedNext
                  }
                  logger.info(s"Set harvest cron: $next")
                  info.setHarvestCron(next)
                  val justDate = harvestCron.unit == DelayUnit.WEEKS
                  val strategy = if (harvestCron.incremental) ModifiedAfter(harvestCron.previous, justDate) else FromScratchIncremental
                  val startHarvest = StartHarvest(strategy)

                  logger.info(s"$info acquired semaphore. permits available ${orgContext.semaphore.availablePermits()}")
                  logger.info(s"$info incremental harvest kickoff $startHarvest")
                  orgContext.orgActor ! info.createMessage(startHarvest)
                } else {
                  val sem = orgContext.semaphore
                  logger.info(
                    s"$info skipping, no semaphore available: ${sem.availablePermits()} of ${sem.size()}; ${sem.activeSpecs().toString()}")
                }
            }
          }
        case Failure(_) => ()
      }
  }
}





