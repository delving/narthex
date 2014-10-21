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

package actors

import akka.actor.ActorRef
import play.api.Logger
import services.FileHandling.ReadProgress
import services.{DatasetDb, DatasetState}

/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

object ProgressReporter {
  def apply(datasetState: DatasetState, datasetDb: DatasetDb): ProgressReporter =
    new UpdatingProgressReporter(datasetState, datasetDb, None)

  def apply(datasetState: DatasetState, datasetDb: DatasetDb, readProgress: ReadProgress): ProgressReporter =
    new UpdatingProgressReporter(datasetState, datasetDb, Some(readProgress))

  def apply(): ProgressReporter = new FakeProgressReporter
}

trait ProgressReporter {
  var bomb: Option[ActorRef]

  def keepReading: Boolean

  def keepWorking: Boolean

  def sendPercent(percent: Int): Boolean

  def sendWorkers(workerCount: Int): Boolean

  def sendPage(page: Int): Boolean
}

class FakeProgressReporter extends ProgressReporter {
  var bomb: Option[ActorRef] = None

  override def keepReading: Boolean = true

  override def keepWorking: Boolean = true

  override def sendPercent(percent: Int): Boolean = true
  
  override def sendWorkers(workerCount: Int): Boolean = true

  override def sendPage(page: Int): Boolean = true
}

class UpdatingProgressReporter(datasetState: DatasetState, datasetDb: DatasetDb, readProgressOption: Option[ReadProgress] = None) extends ProgressReporter {
  val PATIENCE_MILLIS = 333
  var bomb: Option[ActorRef] = None
  var pageCount = 0
  var percentWas = -1
  var lastProgress = 0l

  def sendPercent(percent: Int): Boolean = {
    if (bomb.isDefined) {
      // maybe use the actor ref
      false
    }
    else {
      datasetDb.setStatus(datasetState, percent = percent)
      true
    }
  }

  def sendWorkers(workerCount: Int): Boolean = {
    if (bomb.isDefined) {
      // maybe use the actor ref
      false
    }
    else {
      datasetDb.setStatus(datasetState, workers = workerCount)
      true
    }
  }

  def keepReading: Boolean = {
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
        !bomb.isDefined
      }
    } getOrElse {
      Logger.warn("Expecting readProgress")
      false
    }
  }

  def keepWorking: Boolean = !bomb.isDefined

  override def sendPage(page: Int): Boolean = {
    pageCount += 1
    val percentZero = (pageCount % 1000) / 10
    val percent = if (percentZero == 0) 1 else percentZero
    if (percent > percentWas && (System.currentTimeMillis() - lastProgress) > PATIENCE_MILLIS) {
      val running = sendPercent(percent)
      percentWas = percent
      lastProgress = System.currentTimeMillis()
      running
    }
    else {
      !bomb.isDefined
    }
  }
}
