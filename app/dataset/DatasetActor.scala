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

package dataset

import java.io.File

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import analysis.Analyzer
import analysis.Analyzer.{AnalysisComplete, AnalyzeFile}
import dataset.DatasetActor._
import dataset.DatasetState.SOURCED
import harvest.Harvester
import harvest.Harvester.{HarvestAdLib, HarvestComplete, HarvestPMH, IncrementalHarvest}
import harvest.Harvesting.HarvestType._
import mapping.CategoryCounter
import mapping.CategoryCounter.{CategoryCountComplete, CountCategories}
import org.joda.time.DateTime
import record.Saver
import record.Saver._

import scala.concurrent.duration._
import scala.language.postfixOps

/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

object DatasetActor {

  case class StartHarvest(modifiedAfter: Option[DateTime], justDate: Boolean)

  case class StartAnalysis()

  case class IncrementalSave(modifiedAfter: DateTime, file: File)

  case class StartSaving(incrementalOpt: Option[IncrementalSave])

  case class StartCategoryCounting()

  case class InterruptWork()

  case class InterruptChild(actorWaiting: ActorRef)

  case class ChildFailure(message: String, exceptionOpt: Option[Throwable] = None)

  def props(datasetRepo: DatasetRepo) = Props(new DatasetActor(datasetRepo))

}

class DatasetActor(val datasetRepo: DatasetRepo) extends Actor with ActorLogging {

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
    case _: Exception => Stop
  }

  val db = datasetRepo.datasetDb

  def receive = {

    // === harvest

    case StartHarvest(modifiedAfter, justDate) =>
      log.info(s"Start harvest modified=$modifiedAfter")
      datasetRepo.dropTree()
      db.infoOpt.foreach { info =>
        harvestTypeFromInfo(info).map { harvestType =>
          val harvest = info \ "harvest"
          val url = (harvest \ "url").text
          val database = (harvest \ "dataset").text
          val prefix = (harvest \ "prefix").text
          val kickoff = harvestType match {
            case PMH => HarvestPMH(url, database, prefix, modifiedAfter, justDate)
            case PMH_REC => HarvestPMH(url, database, prefix, modifiedAfter, justDate)
            case ADLIB => HarvestAdLib(url, database, modifiedAfter)
          }
          val harvester = context.child("harvester").getOrElse(context.actorOf(Harvester.props(datasetRepo), "harvester"))
          harvester ! kickoff
        } getOrElse {
          val incremental = modifiedAfter.map(IncrementalHarvest(_, None))
          self ! ChildFailure(s"Harvest type not recognized")
        }
      }

    case HarvestComplete(incrementalOpt) =>
      log.info(s"Harvest complete incremental=$incrementalOpt")
      db.endProgress(None)
      db.infoOpt.foreach { info =>
        incrementalOpt.map { incremental =>
          db.setStatus(SOURCED) // first harvest
          datasetRepo.dropTree()
          incremental.fileOpt.map { newFile =>
            self ! StartSaving(Some(IncrementalSave(incremental.modifiedAfter, newFile)))
          }
        } getOrElse {
          self ! GenerateSource()
        }
      }
      if (sender != self) sender ! PoisonPill

    // === adopting

    case AdoptSource(file) =>
      val saver = context.child("saver").getOrElse(context.actorOf(Saver.props(datasetRepo), "saver"))
      saver ! AdoptSource(file)

    case SourceAdoptionComplete(file) =>
      log.info(s"Adopted source ${file.getAbsolutePath}")
      datasetRepo.dropTree()
      self ! GenerateSource()

    // === generating

    case GenerateSource() =>
      val saver = context.child("saver").getOrElse(context.actorOf(Saver.props(datasetRepo), "saver"))
      saver ! GenerateSource()

    case SourceGenerationComplete(file, recordCount) =>
      // todo: figure out if there are other things to trigger
      log.info(s"Generated file $file with $recordCount records")
      db.setStatus(SOURCED)
      db.setSource(recordCount > 0, recordCount)
      self ! StartAnalysis()

    // === analyzing

    case StartAnalysis() =>
      log.info("Start analysis")
      if (datasetRepo.mappedFile.exists()) {
        val analyzer = context.child("analyzer").getOrElse(context.actorOf(Analyzer.props(datasetRepo), "analyzer"))
        analyzer ! AnalyzeFile(datasetRepo.mappedFile)
      } else datasetRepo.rawFile.map { rawFile =>
        val analyzer = context.child("analyzer").getOrElse(context.actorOf(Analyzer.props(datasetRepo), "analyzer"))
        analyzer ! AnalyzeFile(rawFile)
      } getOrElse {
        // initially for testing (remove the mapped file), but maybe this is ok for always
        self ! GenerateSource()
//        self ! AnalysisComplete(Some(s"No file to analyze for $datasetRepo"))
      }

    case AnalysisComplete(errorOption) =>
      log.info(s"Analysis complete error=$errorOption")
      db.endProgress(errorOption)
      if (errorOption.isDefined)
        datasetRepo.dropTree()
      else
        db.setTree(ready = true)
      if (sender != self) sender ! PoisonPill

    // === saving

    case StartSaving(incrementalOpt) =>
      log.info(s"Start saving from incremental=$incrementalOpt")
      val saver = context.child("saver").getOrElse(context.actorOf(Saver.props(datasetRepo), "saver"))
      saver ! SaveRecords(incrementalOpt)

    case SaveComplete(recordCount) =>
      log.info(s"Save complete $recordCount")
      db.endProgress(None)
      db.setRecords(ready = true, recordCount)
      if (sender != self) sender ! PoisonPill

    // == categorization

    case StartCategoryCounting() =>
      log.info("Start categorizing")
      if (datasetRepo.mappedFile.exists()) {
        val categoryCounter = context.child("category-counter").getOrElse(context.actorOf(CategoryCounter.props(datasetRepo), "category-counter"))
        categoryCounter ! CountCategories()
      }
      else {
        self ! ChildFailure(s"No source file for categorizing $datasetRepo")
      }

    case CategoryCountComplete(dataset, categoryCounts) =>
      log.info(s"Category Counts arrived: $categoryCounts")
      db.endProgress(None)
      context.parent ! CategoryCountComplete(datasetRepo.datasetName, categoryCounts)
      if (sender != self) sender ! PoisonPill

    // === stopping stuff

    case InterruptChild(actorWaiting) => // return true if an interrupt happened
      val interrupted = context.children.exists { child: ActorRef =>
        log.info(s"Sending interrupt to $child")
        child ! InterruptWork()
        true // found one to interrupt
      }
      log.info(s"InterruptChild, report back to $actorWaiting interrupted=$interrupted")
      actorWaiting ! interrupted

    case ChildFailure(message, exceptionOpt) =>
      db.endProgress(Some(message))
      exceptionOpt.map(ex => log.error(ex, message)).getOrElse(log.error(message))
      if (sender != self) sender ! PoisonPill

    case spurious =>
      log.error(s"Spurious message: $spurious")
  }
}









