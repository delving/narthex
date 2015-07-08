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

package mapping

import akka.actor.{Actor, ActorLogging, Props}
import dataset.DatasetActor.WorkFailure
import dataset.ProcessedRepo.{GraphChunk, GraphReader}
import dataset.{DsInfo, ProcessedRepo}
import mapping.CategoriesSpreadsheet.CategoryCount
import mapping.CategoryCounter.{CategoryCountComplete, CountCategories, Counter}
import mapping.VocabInfo._
import org.OrgContext.actorWork
import org.joda.time.DateTime
import services.ProgressReporter
import services.ProgressReporter.ProgressState._
import services.StringHandling._
import triplestore.GraphProperties._
import triplestore.TripleStore

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext

object CategoryCounter {

  case object CountCategories

  case class CategoryCountComplete(dataset: String, categoryCounts: List[CategoryCount])

  case class Counter(var count: Int)

  def props(dsInfo: DsInfo, repo: ProcessedRepo)(implicit ec: ExecutionContext, ts: TripleStore) = Props(new CategoryCounter(dsInfo, repo))
}

class CategoryCounter(dsInfo: DsInfo, repo: ProcessedRepo)(implicit ec: ExecutionContext, ts: TripleStore) extends Actor with ActorLogging {

  val spec = dsInfo.getLiteralProp(datasetSpec).get
  val skosFieldValues = dsInfo.getLiteralPropList(skosField)

  var recordCount = 0
  val countMap = new collection.mutable.HashMap[String, Counter]()
  val saveTime = new DateTime()

  def increment(key: String): Unit = countMap.getOrElseUpdate(key, new Counter(1)).count += 1

  def output(categories: Set[String]) = {
    for (
      a <- categories
    ) yield increment(a)
    increment("NULL")
    for (
      a <- categories;
      b <- categories if b > a
    ) yield increment(s"$a-$b")
    increment("NULL-NULL")
    for (
      a <- categories;
      b <- categories if b > a;
      c <- categories if c > b
    ) yield increment(s"$a-$b-$c")
    increment("NULL-NULL-NULL")
    recordCount += 1
  }

  var reader: Option[GraphReader] = None
  var progressOpt: Option[ProgressReporter] = None
  var termCatMapOpt: Option[Map[String, List[String]]] = None

  def failure(ex: Throwable) = {
    reader.foreach(_.close())
    reader = None
    context.parent ! WorkFailure(ex.getMessage, Some(ex))
  }

  def sendGraphChunkOpt() = {
    try {
      progressOpt.get.checkInterrupt()
      self ! reader.get.readChunkOpt
    }
    catch {
      case ex: Throwable => failure(ex)
    }
  }

  def receive = {

    case CountCategories => actorWork(context) {
      log.info("Counting categories")
      val progressReporter = ProgressReporter(SAVING, context.parent)
      progressOpt = Some(progressReporter)
      reader = Some(repo.createGraphReader(None, saveTime, progressReporter))
      val termMap = withVocabInfo(CATEGORIES_SPEC)(dsInfo.termCategoryMap)
      termCatMapOpt = Some(termMap)
      sendGraphChunkOpt()
    }

    case Some(chunk: GraphChunk) => actorWork(context) {
      log.info("Category count of graphs")
      val termCat = termCatMapOpt.get
      chunk.dataset.listNames().toList.foreach { recordGraph =>
        val model = chunk.dataset.getNamedModel(recordGraph)
        var categoryLabels = Set.empty[String]
        skosFieldValues.foreach { skosFieldValue =>
          val parts = skosFieldValue.split("=")
          val propertyTag = parts(0)
          val propertyUri = parts(1)
          val property = model.getProperty(propertyUri)
          model.listObjectsOfProperty(property).map(_.asLiteral().getString).toList.foreach { literalValueText =>
            val mintedUri = s"${dsInfo.uri}/$propertyTag/${slugify(literalValueText)}"
            termCat.get(mintedUri).foreach(newLabels => categoryLabels ++= newLabels)
          }
        }
        output(categoryLabels)
      }
      sendGraphChunkOpt()
    }

    case None => actorWork(context) {
      reader.foreach(_.close())
      reader = None
      log.info(s"All categories counted for $spec")
      val categoryCounts = countMap.map(count => CategoryCount(count._1, count._2.count, spec)).toList
      context.parent ! CategoryCountComplete(spec, categoryCounts)
    }
  }
}