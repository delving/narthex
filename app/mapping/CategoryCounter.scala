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
import dataset.DatasetActor.{InterruptWork, WorkFailure}
import dataset.DatasetContext
import mapping.CategoryCounter.{CategoryCountComplete, CountCategories}
import record.CategoryParser
import record.CategoryParser.CategoryCount
import record.PocketParser._
import services.ProgressReporter.ProgressState._
import services.{FileHandling, NarthexConfig, ProgressReporter}

import scala.concurrent._

object CategoryCounter {

  case class CountCategories()

  case class CategoryCountComplete(dataset: String, categoryCounts: List[CategoryCount])

  def props(datasetContext: DatasetContext) = Props(new CategoryCounter(datasetContext))
}

class CategoryCounter(val datasetContext: DatasetContext) extends Actor with ActorLogging {

  import context.dispatcher

  var progress: Option[ProgressReporter] = None

  def receive = {

    case InterruptWork =>
      if (!progress.exists(_.interruptBy(sender()))) context.stop(self)

    case CountCategories() =>
      log.info("Counting categories")
      val pathPrefix = s"${NarthexConfig.ORG_ID}/$datasetContext"
      future {
        val categoryMappings = datasetContext.categoryDb.getMappings.map(cm => (cm.source, cm)).toMap
        val parser = new CategoryParser(pathPrefix, POCKET_RECORD_ROOT, POCKET_UNIQUE_ID, POCKET_DEEP_RECORD_ROOT, categoryMappings)
        val (source, readProgress) = FileHandling.sourceFromFile(datasetContext.processedRepo.home)
        try {
          val progressReporter = ProgressReporter(CATEGORIZING, context.parent)
          progress = Some(progressReporter)
          progressReporter.setReadProgress(readProgress)
          parser.parse(source, Set.empty[String], progressReporter)
          context.parent ! CategoryCountComplete(datasetContext.dsInfo.spec, parser.categoryCounts)
        }
        catch {
          case e: Exception => context.parent ! WorkFailure(e.getMessage, Some(e))
        }
        finally {
          source.close()
        }
      } onFailure {
        case t => context.parent ! WorkFailure(t.getMessage, Some(t))
      }
  }
}