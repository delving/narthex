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
import harvest.PeriodicHarvest.ScanForHarvests
import play.api.Logger

import scala.language.postfixOps

object PeriodicHarvest {

  case object ScanForHarvests

  def props() = Props[PeriodicHarvest]

}

class PeriodicHarvest extends Actor {

  val log = Logger.logger

  def receive = {

    case ScanForHarvests =>
      Logger.warn("Periodic harvest not implemented")
//      OrgRepo.repo.orgDb.listDatasets.foreach { dataset =>
//        val harvestCron = Harvesting.harvestCron(dataset.info)
//        if (harvestCron.timeToWork) {
//          log.info(s"Time to work on ${dataset.datasetName}")
//          val datasetContext = OrgRepo.repo.datasetContext(dataset.datasetName)
//          datasetContext.nextHarvest()
//        }
//      }
  }
}





