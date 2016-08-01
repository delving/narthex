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
    val PERCENT = ProgressType("progress-percent")
    val WORKERS = ProgressType("progress-workers")
    val PAGES = ProgressType("progress-pages")

    val ALL_PROGRESS_TYPES = List(TYPE_IDLE, PERCENT, WORKERS, PAGES)

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
    val SAVING = ProgressState("state-saving")
    val SKOSIFYING = ProgressState("state-skosifying")
    val ERROR = ProgressState("state-error")

    val ALL_STATES = List(STATE_IDLE, HARVESTING, COLLECTING, ADOPTING, GENERATING, SPLITTING, COLLATING, CATEGORIZING, PROCESSING, SAVING, SKOSIFYING, ERROR)

    def progressStateFromString(string: String): Option[ProgressState] = ALL_STATES.find(s => s.matches(string))

    def progressStateFromInfo(datasetInfo: NodeSeq) = progressStateFromString((datasetInfo \ "progress" \ "state").text)
  }

  def apply(progressState: ProgressState, datasetActor: ActorRef) = new UpdatingProgressReporter(progressState, datasetActor)

  def apply(): ProgressReporter = new FakeProgressReporter

  class InterruptedException() extends Exception("Manually Interrupted")
}

trait ProgressReporter {

  def interrupt(): Unit

  def checkInterrupt(): Unit

  def sendValue(value: Option[Int] = None): Unit

  def sendPercent(percent: Int): Unit

  def sendWorkers(workerCount: Int): Unit

  def sendPage(page: Int): Unit

  def setMaximum(max: Int): Unit

  def setReadProgress(readProgress: ReadProgress): Unit

}

class FakeProgressReporter extends ProgressReporter {

  override def interrupt() = {}

  override def checkInterrupt() = {}

  override def sendValue(value: Option[Int]): Unit = {}

  override def sendPercent(percent: Int): Unit = {}

  override def sendWorkers(workerCount: Int): Unit = {}

  override def sendPage(page: Int): Unit = {}

  override def setMaximum(max: Int): Unit = {}

  override def setReadProgress(readProgress: ReadProgress): Unit = {}
}

class UpdatingProgressReporter(progressState: ProgressState, datasetActor: ActorRef) extends ProgressReporter {
  val PATIENCE_MILLIS = 100
  var interrupted = false
  var readProgressOption: Option[ReadProgress] = None
  var maximumOption: Option[Int] = None
  var percentWas = -1
  var lastProgress = 0l

  override def interrupt() = interrupted = true

  override def checkInterrupt() = if (interrupted) throw new InterruptedException

  override def sendPercent(percent: Int) = {
    checkInterrupt()
    datasetActor ! ProgressTick(Option(this), progressState, PERCENT, percent)
  }

  override def sendPage(pageNumber: Int) = {
    checkInterrupt()
    val percentZero = pageNumber
    val percent = if (percentZero == 0) 1 else percentZero
    if (percent > percentWas && (System.currentTimeMillis() - lastProgress) > PATIENCE_MILLIS) {
      def sendPageNumber(pageNumber: Int) = {
        checkInterrupt()
        datasetActor ! ProgressTick(Option(this), progressState, PAGES, pageNumber)
      }
      sendPageNumber(percent)
      percentWas = percent
      lastProgress = System.currentTimeMillis()
    }
  }

  override def setMaximum(max: Int): Unit = maximumOption = Some(max)

  override def setReadProgress(readProgress: ReadProgress): Unit = {
    checkInterrupt()
    readProgressOption = Some(readProgress)
  }

  override def sendWorkers(workerCount: Int) = {
    checkInterrupt()
    datasetActor ! ProgressTick(Option(this), progressState, WORKERS, workerCount)
  }

  override def sendValue(value: Option[Int]): Unit = {
    readProgressOption match {
      case Some(readProgress) =>
        val percentZero = readProgress.getPercentRead
        val percent = if (percentZero == 0) 1 else percentZero
        if (percent > percentWas && (System.currentTimeMillis() - lastProgress) > PATIENCE_MILLIS) {
          val running = sendPercent(percent)
          percentWas = percent
          lastProgress = System.currentTimeMillis()
          running
        }
      case None =>
        maximumOption match {
          case Some(maximum) =>
            val percentZero = (100 * value.get) / maximum
            val percent = if (percentZero == 0) 1 else percentZero
            if (percent > percentWas && (System.currentTimeMillis() - lastProgress) > PATIENCE_MILLIS) {
              val running = sendPercent(percent)
              percentWas = percent
              lastProgress = System.currentTimeMillis()
              running
            }
          case None =>
            Logger.warn("Expecting readProgress or maximum")
        }
    }
  }
}
