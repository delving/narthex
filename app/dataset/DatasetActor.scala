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
import dataset.DatasetState._
import dataset.ProgressState._
import dataset.ProgressType._
import harvest.Harvester
import harvest.Harvester.{HarvestAdLib, HarvestComplete, HarvestPMH}
import harvest.Harvesting.HarvestType._
import mapping.CategoryCounter
import mapping.CategoryCounter.{CategoryCountComplete, CountCategories}
import org.OrgActor.DatasetQuestion
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FileUtils._
import org.joda.time.DateTime
import record.Saver
import record.Saver._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.xml.Elem

/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

object DatasetActor {

  // state machine

  sealed trait DatasetActorState

  case object Idle extends DatasetActorState

  case object Harvesting extends DatasetActorState

  case object Adopting extends DatasetActorState

  case object Analyzing extends DatasetActorState

  case object Generating extends DatasetActorState

  case object Saving extends DatasetActorState

  case object Categorizing extends DatasetActorState

  trait DatasetActorData

  case object Dormant extends DatasetActorData

  case class Active(childOpt: Option[ActorRef], progressState: ProgressState, progressType: ProgressType = TYPE_IDLE, count: Int = 0) extends DatasetActorData

  case class InError(error: String) extends DatasetActorData


  // messages to receive

  case class Command(name: String)

  case class StartHarvest(info: Elem, modifiedAfter: Option[DateTime], justDate: Boolean)

  case object StartAnalysis

  case class IncrementalSave(modifiedAfter: DateTime, file: File)

  case class StartSaving(incrementalOpt: Option[IncrementalSave])

  case object StartCategoryCounting

  case object InterruptWork

  case class WorkFailure(message: String, exceptionOpt: Option[Throwable] = None)

  case object CheckState

  case object ClearError

  case class ProgressTick(progressState: ProgressState, progressType: ProgressType = TYPE_IDLE, count: Int = 0)

  // create one

  def props(datasetRepo: DatasetRepo) = Props(new DatasetActor(datasetRepo))

}

class DatasetActor(val datasetRepo: DatasetRepo) extends FSM[DatasetActorState, DatasetActorData] with ActorLogging {

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
    case _: Exception => Stop
  }

  val db = datasetRepo.datasetDb

  val errorMessage = db.infoOpt.map(info => (info \ "error" \ "message").text).getOrElse("")

  startWith(Idle, if (errorMessage.nonEmpty) InError(errorMessage) else Dormant)

  when(Idle) {

    case Event(StartHarvest(info, modifiedAfter, justDate), Dormant) =>
      datasetRepo.dropTree()
      val harvest = info \ "harvest"
      harvestTypeFromString((harvest \ "harvestType").text).map { harvestType =>
        val url = (harvest \ "url").text
        val database = (harvest \ "dataset").text
        val prefix = (harvest \ "prefix").text
        val search = (harvest \ "search").text
        val kickoff = harvestType match {
          case PMH => HarvestPMH(url, database, prefix, modifiedAfter, justDate)
          case PMH_REC => HarvestPMH(url, database, prefix, modifiedAfter, justDate)
          case ADLIB => HarvestAdLib(url, database, search, modifiedAfter)
        }
        val harvester = context.actorOf(Harvester.props(datasetRepo), "harvester")
        harvester ! kickoff
        goto(Harvesting) using Active(Some(harvester), HARVESTING)
      } getOrElse {
        stay() using InError("Unable to determine harvest type")
      }

    case Event(AdoptSource(file), Dormant) =>
      val saver = context.actorOf(Saver.props(datasetRepo), "saver")
      saver ! AdoptSource(file)
      goto(Adopting) using Active(Some(saver), ADOPTING)

    case Event(GenerateSource, Dormant) =>
      val saver = context.child("saver").getOrElse(context.actorOf(Saver.props(datasetRepo), "saver"))
      saver ! GenerateSource
      goto(Generating) using Active(Some(saver), GENERATING)

    case Event(StartAnalysis, Dormant) =>
      log.info("Start analysis")
      if (datasetRepo.mappedFile.exists()) {
        val analyzer = context.child("analyzer").getOrElse(context.actorOf(Analyzer.props(datasetRepo), "analyzer"))
        analyzer ! AnalyzeFile(datasetRepo.mappedFile)
        goto(Analyzing) using Active(Some(analyzer), SPLITTING)
      } else datasetRepo.rawFile.map { rawFile =>
        val analyzer = context.child("analyzer").getOrElse(context.actorOf(Analyzer.props(datasetRepo), "analyzer"))
        analyzer ! AnalyzeFile(rawFile)
        goto(Analyzing) using Active(Some(analyzer), SPLITTING)
      } getOrElse {
        self ! GenerateSource
        stay()
      }

    case Event(StartSaving(incrementalOpt), Dormant) =>
      val saver = context.child("saver").getOrElse(context.actorOf(Saver.props(datasetRepo), "saver"))
      saver ! SaveRecords(incrementalOpt)
      goto(Saving) using Active(Some(saver), SAVING)

    case Event(StartCategoryCounting, Dormant) =>
      if (datasetRepo.mappedFile.exists()) {
        val categoryCounter = context.child("category-counter").getOrElse(context.actorOf(CategoryCounter.props(datasetRepo), "category-counter"))
        categoryCounter ! CountCategories()
        goto(Categorizing) using Active(Some(categoryCounter), CATEGORIZING)
      }
      else {
        stay() using InError(s"No source file for categorizing $datasetRepo")
      }

    case Event(DatasetQuestion(listener, Command(commandName)), Dormant) =>
      val reply = commandName match {

        case "delete" =>
          datasetRepo.datasetDb.dropDataset()
          deleteQuietly(datasetRepo.rootDir)
          "deleted"

        case "remove source" =>
          deleteQuietly(datasetRepo.rawDir)
          deleteQuietly(datasetRepo.stagingDir)
          datasetRepo.datasetDb.setStatus(EMPTY)
          "source removed"

        case "remove mapped" =>
          deleteQuietly(datasetRepo.pocketFile)
          deleteQuietly(datasetRepo.mappedFile)
          datasetRepo.startAnalysis() // todo: shouldn't be necessary
          "mapped removed"

        case "remove tree" =>
          deleteQuietly(datasetRepo.treeDir)
          datasetRepo.datasetDb.setTree(ready = false)
          "tree removed"

        case "remove records" =>
          datasetRepo.recordDbOpt.map(_.dropDb())
          datasetRepo.datasetDb.setRecords(ready = false, 0)
          "records removed"

        case _ =>
          log.warning(s"$this sent unrecognized command $commandName")
          "unrecognized"
      }
      listener ! reply
      stay()
  }

  when(Harvesting) {

    case Event(HarvestComplete(incrementalOpt), active: Active) =>
      incrementalOpt.map { incremental =>
        db.setStatus(SOURCED) // first harvest
        datasetRepo.dropTree()
        incremental.fileOpt.map { newFile =>
          self ! StartSaving(Some(IncrementalSave(incremental.modifiedAfter, newFile)))
        }
      } getOrElse {
        self ! GenerateSource
      }
      active.childOpt.map(_ ! PoisonPill)
      goto(Idle) using Dormant

  }

  when(Adopting) {

    case Event(SourceAdoptionComplete(file), active: Active) =>
      datasetRepo.dropTree()
      self ! GenerateSource
      active.childOpt.map(_ ! PoisonPill)
      goto(Idle) using Dormant

  }

  when(Generating) {

    case Event(SourceGenerationComplete(rawRecordCount, mappedRecordCount), active: Active) =>
      log.info(s"Generated file $rawRecordCount raw, $mappedRecordCount mapped")
      if (mappedRecordCount > 0) {
        db.setStatus(SOURCED)
        db.setSource(ready = true, mappedRecordCount)
      }
      else {
        val rawFile = datasetRepo.createRawFile(datasetRepo.pocketFile.getName)
        FileUtils.copyFile(datasetRepo.pocketFile, rawFile)
        db.setStatus(RAW_POCKETS)
      }
      self ! StartAnalysis
      active.childOpt.map(_ ! PoisonPill)
      goto(Idle) using Dormant

  }

  when(Analyzing) {

    case Event(AnalysisComplete(errorOption), active: Active) =>
      if (errorOption.isDefined)
        datasetRepo.dropTree()
      else
        db.setTree(ready = true)
      active.childOpt.map(_ ! PoisonPill)
      goto(Idle) using Dormant

  }

  when(Saving) {

    case Event(SaveComplete(recordCount), active: Active) =>
      db.setRecords(ready = true, recordCount)
      active.childOpt.map(_ ! PoisonPill)
      goto(Idle) using Dormant

  }

  when(Categorizing) {

    case Event(CategoryCountComplete(dataset, categoryCounts), active: Active) =>
      context.parent ! CategoryCountComplete(datasetRepo.datasetName, categoryCounts)
      active.childOpt.map(_ ! PoisonPill)
      goto(Idle) using Dormant

  }

  whenUnhandled {

    case Event(tick: ProgressTick, active: Active) =>
      stay() using active.copy(progressState = tick.progressState, progressType = tick.progressType, count = tick.count)

    case Event(ClearError, InError(message)) =>
      log.info(s"Cleared error: $message)")
      goto(Idle) using Dormant

    case Event(InterruptWork, active: Active) =>
      log.info(s"Sending interrupt while in $stateName/$active)")
      active.childOpt.map { child =>
        log.info(s"Interrupting $child")
        child ! InterruptWork
      }
      stay()

    case Event(WorkFailure(message, exceptionOpt), active: Active) =>
      log.warning(s"Child failure $message while in $active")
      exceptionOpt.map(log.warning(message, _))
      db.setError(message)
      exceptionOpt.map(ex => log.error(ex, message)).getOrElse(log.error(message))
      active.childOpt.map(_ ! PoisonPill)
      goto(Idle) using InError(message)

    case Event(DatasetQuestion(listener, CheckState), data) =>
      listener ! data
      stay()

    case Event(request, data) =>
      log.warning(s"Unhandled request $request in state $stateName/$data")
      stay()
  }

}









