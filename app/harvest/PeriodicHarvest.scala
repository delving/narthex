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
import dataset.DsInfo
import harvest.PeriodicHarvest.ScanForHarvests
import org.OrgContext.ts
import play.api.Logger

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
          list.map { dsInfo =>
            val harvestCron = dsInfo.harvestCron
            if (harvestCron.timeToWork) {
              log.info(s"Time to work on $dsInfo")
              Logger.warn(s"Periodic wants to harvest $dsInfo")
              // todo: let's see it asking for a while, then test
              //              orgContext.datasetContext(dsInfo.spec).nextHarvest()
            }
          }

      }
  }
}





