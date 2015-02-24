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

package org

import akka.actor._
import dataset.DatasetActor._
import mapping.CategoriesSpreadsheet.CategoryCount
import mapping.CategoryCounter.CategoryCountComplete
import org.OrgActor._
import org.OrgContext.orgContext
import play.libs.Akka

import scala.language.postfixOps

/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

object OrgActor {

  lazy val actor: ActorRef = Akka.system.actorOf(Props[OrgActor], orgContext.orgId)

  case class DatasetMessage(spec: String, message: AnyRef, question: Boolean = false)

  case class DatasetQuestion(requester: ActorRef, question: AnyRef)

  case class DatasetsCountCategories(datasets: Seq[String])

}


class OrgActor extends Actor with ActorLogging {

  var countsInProgress = Map.empty[String, Option[List[CategoryCount]]]

  def receive = {

    case DatasetMessage(spec, message, question) =>
      val actor: ActorRef = context.child(spec).getOrElse {
        val datasetContext = orgContext.datasetContext(spec)
        val datasetActor = context.actorOf(props(datasetContext), spec)
        log.info(s"Created dataset actor $datasetActor")
        context.watch(datasetActor)
        datasetActor
      }
      if (question) {
        actor ! DatasetQuestion(sender(), message)
      } else {
        actor ! message
      }

    case DatasetsCountCategories(datasets) =>
      countsInProgress = datasets.map(name => (name, None)).toMap
      datasets.foreach(name => self ! DatasetMessage(name, StartCategoryCounting))

    case CategoryCountComplete(dataset, categoryCounts) =>
      countsInProgress += dataset -> Some(categoryCounts)
      log.info(s"Category counting complete, counts: $countsInProgress")
      val countLists = countsInProgress.values.flatten
      if (countLists.size == countsInProgress.size) {
        OrgContext.orgContext.categoriesRepo.createSheet(countLists.flatten.toList)
        countsInProgress = Map.empty[String, Option[List[CategoryCount]]]
      }

    case Terminated(name) =>
      log.info(s"Demised $name")
      log.info(s"Children: ${context.children}")

    case spurious =>
      log.warning(s"Spurious message $spurious")
  }
}



