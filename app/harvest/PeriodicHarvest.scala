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
import dataset.DatasetActor.{FromScratchIncremental, ModifiedAfter, StartHarvest}
import dataset.DsInfo
import dataset.DsInfo.{DsState, withDsInfo}
import harvest.PeriodicHarvest.ScanForHarvests
import organization.OrgContext
import play.api.Logger
import services.Temporal.DelayUnit

import scala.language.postfixOps

object PeriodicHarvest {

  case object ScanForHarvests

  def props(orgContext: OrgContext) = Props(new PeriodicHarvest(orgContext))

  val harvestingAllowed = List(DsState.SAVED, DsState.INCREMENTAL_SAVED)
}

class PeriodicHarvest(orgContext: OrgContext) extends Actor {

  val log = Logger.logger
  import context.dispatcher
  implicit val ts = orgContext.ts


  def receive = {

    case ScanForHarvests =>
      val futureList = DsInfo.listDsInfo(orgContext)
      futureList.onSuccess {
        case list: List[DsInfo] =>
          list.
            filter(info => PeriodicHarvest.harvestingAllowed.contains(info.getState())).
            foreach { listedInfo =>
              val harvestCron = listedInfo.currentHarvestCron
              if (harvestCron.timeToWork) withDsInfo(listedInfo.spec, orgContext) { info => // the cached version
                log.info(s"Time to work on $info: $harvestCron")
                val proposedNext = harvestCron.next
                val next = if (proposedNext.timeToWork) {
                  val revised = harvestCron.now
                  log.info(s"$info next harvest $proposedNext is already due so adjusting to 'now': $revised")
                  revised
                }
                else {
                  log.info(s"$info next harvest : $proposedNext")
                  proposedNext
                }
                log.info(s"Set harvest cron: $next")
                info.setHarvestCron(next)
                val justDate = harvestCron.unit == DelayUnit.WEEKS
                val strategy = if (harvestCron.incremental) ModifiedAfter(harvestCron.previous, justDate) else FromScratchIncremental
                val startHarvest = StartHarvest(strategy)
                log.info(s"$info incremental harvest kickoff $startHarvest")
                orgContext.orgActor ! info.createMessage(startHarvest)
            }
          }

      }
  }
}





