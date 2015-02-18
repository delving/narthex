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
import dataset.DatasetActor.StartHarvest
import dataset.DsInfo
import dataset.DsInfo.withDsInfo
import harvest.PeriodicHarvest.ScanForHarvests
import org.OrgActor
import org.OrgContext.ts
import play.api.Logger
import services.Temporal.DelayUnit

import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps

object PeriodicHarvest {

  case object ScanForHarvests

  def props() = Props[PeriodicHarvest]

}

class PeriodicHarvest extends Actor {

  val log = Logger.logger

  def receive = {

    case ScanForHarvests =>
      val futureList = DsInfo.listDsInfo(ts)
      futureList.onSuccess {
        case list: List[DsInfo] =>
          list.map { listedInfo =>
            val harvestCron = listedInfo.currentHarvestCron
            if (harvestCron.timeToWork) withDsInfo(listedInfo.spec) { info => // the cached version
              log.info(s"Time to work on $info")
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
              info.setHarvestCron(next)
              val justDate = harvestCron.unit == DelayUnit.WEEKS
              val startHarvest = StartHarvest(Some(harvestCron.previous), justDate)
              log.info(s"$info incremental harvest kickoff $startHarvest")
              OrgActor.actor ! info.createMessage(startHarvest)
            }
          }

      }
  }
}





