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

package actors

import akka.actor.{Actor, Props}
import play.api.libs.concurrent.Execution.Implicits._
import play.libs.Akka
import services.{Harvesting, OrgRepo}

import scala.concurrent.duration._
/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

object HarvestTicker {

  def props() = Props[HarvestTicker]

  def startTicker() = {
    val harvestTicker = Akka.system.actorOf(props(), "HarvestTicker")
    Akka.system.scheduler.schedule(10.seconds, 10.seconds, harvestTicker, "tick")
  }

}


class HarvestTicker extends Actor {

  def receive = {

    case "tick" =>
      OrgRepo.repo.repoDb.listDatasets.foreach { dataset =>
        val harvestCron = Harvesting.harvestCron(dataset.info)
        if (harvestCron.timeToWork) {
          println(s"Time to work on ${dataset.name}")
          val datasetRepo = OrgRepo.repo.datasetRepo(dataset.name)
          datasetRepo.nextHarvest()
        }
      }
  }
}
