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
import dataset.ProgressState
import dataset.ProgressType._
import play.api.Logger
import services.FileHandling.ReadProgress

/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

object ProgressReporter {

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
