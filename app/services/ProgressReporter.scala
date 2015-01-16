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

package services

import akka.actor.ActorRef
import dataset.DatasetActor.ProgressTick
import play.api.Logger
import services.FileHandling.ReadProgress
import services.ProgressReporter.ProgressType._
import services.ProgressReporter._

import scala.xml.NodeSeq

/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

object ProgressReporter {

  case class ProgressType(name: String) {
    override def toString = name

    def matches(otherName: String) = name == otherName
  }

  object ProgressType {
    val TYPE_IDLE = ProgressType("progress-idle")
    val BUSY = ProgressType("progress-busy")
    val PERCENT = ProgressType("progress-percent")
    val WORKERS = ProgressType("progress-workers")
    val PAGES = ProgressType("progress-pages")

    val ALL_PROGRESS_TYPES = List(TYPE_IDLE, BUSY, PERCENT, WORKERS, PAGES)

    def progressTypeFromString(string: String): Option[ProgressType] = ALL_PROGRESS_TYPES.find(s => s.matches(string))

    def progressTypeFromInfo(datasetInfo: NodeSeq) = progressTypeFromString((datasetInfo \ "progress" \ "type").text)
  }

  case class ProgressState(name: String) {
    override def toString = name

    def matches(otherName: String) = name == otherName
  }

  object ProgressState {
    val STATE_IDLE = ProgressState("state-idle")
    val HARVESTING = ProgressState("state-harvesting")
    val COLLECTING = ProgressState("state-collecting")
    val ADOPTING = ProgressState("state-adopting")
    val GENERATING = ProgressState("state-generating")
    val SPLITTING = ProgressState("state-splitting")
    val COLLATING = ProgressState("state-collating")
    val CATEGORIZING = ProgressState("state-categorizing")
    val PROCESSING = ProgressState("state-processing")
    val ERROR = ProgressState("state-error")

    val ALL_STATES = List(STATE_IDLE, HARVESTING, COLLECTING, ADOPTING, GENERATING, SPLITTING, COLLATING, CATEGORIZING, PROCESSING, ERROR)

    def progressStateFromString(string: String): Option[ProgressState] = ALL_STATES.find(s => s.matches(string))

    def progressStateFromInfo(datasetInfo: NodeSeq) = progressStateFromString((datasetInfo \ "progress" \ "state").text)
  }

  def apply(progressState: ProgressState, datasetActor: ActorRef) = new UpdatingProgressReporter(progressState, datasetActor)

  def apply(): ProgressReporter = new FakeProgressReporter
}

trait ProgressReporter {

  def interruptBy(actor: ActorRef): Boolean

  def keepReading(value: Int = -1): Boolean

  def keepWorking: Boolean

  def sendPercent(percent: Int): Boolean

  def sendWorkers(workerCount: Int): Boolean

  def sendPage(page: Int): Boolean

  def setMaximum(max: Int): Unit

  def setReadProgress(readProgress: ReadProgress): Unit

}

class FakeProgressReporter extends ProgressReporter {

  override def interruptBy(actor: ActorRef) = true

  override def keepReading(value: Int): Boolean = true

  override def keepWorking: Boolean = true

  override def sendPercent(percent: Int): Boolean = true

  override def sendWorkers(workerCount: Int): Boolean = true

  override def sendPage(page: Int): Boolean = true

  override def setMaximum(max: Int): Unit = {}

  override def setReadProgress(readProgress: ReadProgress): Unit = {}
}

class UpdatingProgressReporter(progressState: ProgressState, datasetActor: ActorRef) extends ProgressReporter {
  val PATIENCE_MILLIS = 333
  var bomb: Option[ActorRef] = None
  var readProgressOption: Option[ReadProgress] = None
  var maximumOption: Option[Int] = None
  var percentWas = -1
  var lastProgress = 0l

  override def interruptBy(actor: ActorRef): Boolean = {
    if (bomb.isDefined) {
      false
    }
    else {
      bomb = Some(actor)
      true
    }
  }

  private def mindTheBomb(setProgress: => Unit): Boolean = {
    if (keepWorking) setProgress
    keepWorking
  }

  def sendPercent(percent: Int) = mindTheBomb(datasetActor ! ProgressTick(progressState, PERCENT, percent))

  def sendPageNumber(pageNumber: Int) = mindTheBomb(datasetActor ! ProgressTick(progressState, PAGES, pageNumber))

  def sendWorkers(workerCount: Int) = mindTheBomb(datasetActor ! ProgressTick(progressState, WORKERS, workerCount))

  def keepReading(value: Int): Boolean = {
    readProgressOption.map { readProgress =>
      val percentZero = readProgress.getPercentRead
      val percent = if (percentZero == 0) 1 else percentZero
      if (percent > percentWas && (System.currentTimeMillis() - lastProgress) > PATIENCE_MILLIS) {
        val running = sendPercent(percent)
        percentWas = percent
        lastProgress = System.currentTimeMillis()
        running
      }
      else {
        keepWorking
      }
    } getOrElse {
      maximumOption.map { maximum =>
        val percentZero = (100 * value) / maximum
        val percent = if (percentZero == 0) 1 else percentZero
        if (percent > percentWas && (System.currentTimeMillis() - lastProgress) > PATIENCE_MILLIS) {
          val running = sendPercent(percent)
          percentWas = percent
          lastProgress = System.currentTimeMillis()
          running
        }
        else {
          keepWorking
        }
      } getOrElse {
        Logger.warn("Expecting readProgress or maximum")
        false
      }
    }
  }

  def keepWorking: Boolean = !bomb.isDefined

  override def sendPage(pageNumber: Int): Boolean = {
    val percentZero = pageNumber
    val percent = if (percentZero == 0) 1 else percentZero
    if (percent > percentWas && (System.currentTimeMillis() - lastProgress) > PATIENCE_MILLIS) {
      val running = sendPageNumber(percent)
      percentWas = percent
      lastProgress = System.currentTimeMillis()
      running
    }
    else {
      keepWorking
    }
  }

  override def setMaximum(max: Int): Unit = maximumOption = Some(max)

  override def setReadProgress(readProgress: ReadProgress): Unit = readProgressOption = Some(readProgress)
}
