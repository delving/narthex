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

import java.io.File

import akka.actor.{Actor, Props}
import dataset.DatasetActor.InterruptWork
import dataset.{DatasetRepo, ProgressState}
import mapping.CategoryCounter.{CategoryCountComplete, CountCategories}
import play.api.Logger
import record.CategoryParser
import record.CategoryParser.CategoryCount
import services.{FileHandling, NarthexConfig, ProgressReporter}

import scala.concurrent._

object CategoryCounter {

  case class CountCategories(file: File, recordRoot: String, uniqueId: String)

  case class CategoryCountComplete(dataset: String, categoryCounts: List[CategoryCount], errorOption: Option[String])

  def props(datasetRepo: DatasetRepo) = Props(new CategoryCounter(datasetRepo))
}

class CategoryCounter(val datasetRepo: DatasetRepo) extends Actor {

  import context.dispatcher

  var log = Logger
  var progress: Option[ProgressReporter] = None

  def receive = {

    case InterruptWork() =>
      progress.map(_.bomb = Some(sender())).getOrElse(context.stop(self))

    case CountCategories(file, recordRoot, uniqueId) =>
      log.info(s"Counting categories $datasetRepo: ${file.getAbsolutePath}, $recordRoot, $uniqueId")
      val pathPrefix = s"${NarthexConfig.ORG_ID}/$datasetRepo"
      future {
        val categoryMappings = datasetRepo.categoryDb.getMappings.map(cm => (cm.source, cm)).toMap
        val parser = new CategoryParser(pathPrefix, recordRoot, uniqueId, None, categoryMappings)
        val (source, readProgress) = FileHandling.sourceFromFile(file)
        try {
          val progressReporter = ProgressReporter(ProgressState.CATEGORIZING, datasetRepo.datasetDb)
          progress = Some(progressReporter)
          progressReporter.setReadProgress(readProgress)
          parser.parse(source, Set.empty[String], progressReporter)
          context.parent ! CategoryCountComplete(datasetRepo.datasetName, parser.categoryCounts, None)
        }
        catch {
          case e: Exception => context.parent ! CategoryCountComplete(datasetRepo.datasetName, List.empty[CategoryCount], Some(e.toString))
        }
        finally {
          source.close()
        }
      }
  }
}