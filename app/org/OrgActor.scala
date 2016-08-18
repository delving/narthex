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
import play.libs.Akka

import scala.language.postfixOps

/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

object OrgActor {

  def actor(orgContext: OrgContext): ActorRef = Akka.system.actorOf(Props(new OrgActor(orgContext)), orgContext.appConfig.orgId)

  case class DatasetMessage(spec: String, message: AnyRef)

  case class DatasetsCountCategories(datasets: Seq[String])

}


class OrgActor(val orgContext: OrgContext) extends Actor with ActorLogging {

  var results = Map.empty[String, Option[List[CategoryCount]]]

  def receive = {

    case DatasetMessage(spec, message) =>
      val actor: ActorRef = context.child(spec).getOrElse {
        val datasetContext = orgContext.datasetContext(spec)
        val datasetActor = context.actorOf(props(datasetContext, orgContext.mailService, orgContext), spec)
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

    case Terminated(name) =>
      log.info(s"Demised $name")
      log.info(s"Children: ${context.children}")

    case spurious =>
      log.warning(s"Spurious message $spurious")
  }
}



