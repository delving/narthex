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

import java.io.{BufferedInputStream, File, FileInputStream}

import actors.SourceActor._
import akka.actor.{Actor, ActorRef, Props}
import org.basex.core.cmd.Optimize
import play.api.Logger
import services.DatasetState._
import services.FileHandling.{clearDir, gitCommit}
import services.{DatasetRepo, RecordHandling, SourceRepo}

import scala.language.postfixOps

object SourceActor {

  case class AcceptFile(file: File)

  case class CollectSource()

  case class CollectProgress(percent: Int)

  case class CollectComplete()

//  case class CollectError(error: String)

  case class SaveRecords()

  case class InterruptCollecting()

  def props(datasetRepo: DatasetRepo, sourceRepo: SourceRepo) = Props(new SourceActor(datasetRepo, sourceRepo))
}

class SourceActor(val datasetRepo: DatasetRepo, val sourceRepo: SourceRepo) extends Actor with RecordHandling {
  var log = Logger
  var bomb: Option[ActorRef] = None

  def receive = {

    case InterruptCollecting() =>
      bomb = Some(sender)

    case AcceptFile(file) =>
      val progress = context.actorOf(Props(new Actor() {
        override def receive: Receive = {
          case CollectProgress(percent) =>
            datasetRepo.datasetDb.setStatus(COLLECTING, percent = percent)
        }
      }))
      def sendProgress(percent: Int): Boolean = {
        if (bomb.isDefined) return false
        progress ! CollectProgress(percent)
        true
      }
      // accepting a file means that it's a new version at the moment
      clearDir(datasetRepo.sourceDir)
      log.info(s"Accept file for $datasetRepo")
      val number = sourceRepo.acceptFile(file, sendProgress)
      number.map { num =>
        log.info(s"Accepted $datasetRepo $num")
        self ! CollectSource()
      } getOrElse {
        log.info(s"No records in file $datasetRepo")
        datasetRepo.datasetDb.setStatus(READY)
        context.stop(self)
      }

    case CollectSource() =>
      val progress = context.actorOf(Props(new Actor() {
        override def receive: Receive = {
          case CollectProgress(percent) =>
            datasetRepo.datasetDb.setStatus(COLLECTING, percent = percent)
        }
      }))
      def sendProgress(percent: Int): Boolean = {
        if (bomb.isDefined) return false
        progress ! CollectProgress(percent)
        true
      }
      val sourceFile = sourceRepo.generateSourceFile(sendProgress, datasetRepo.datasetDb.setNamespaceMap)
      try {
        gitCommit(sourceFile, "SourceActor did it")
        datasetRepo.datasetDb.setStatus(READY)
        // todo: complete message
        context.stop(self)
      }
      catch {
        case e: Exception =>
          context.stop(self)
          datasetRepo.datasetDb.setStatus(READY)
          false
      }
      context.stop(progress)

    case CollectComplete() =>
      // todo: not called yet!
      log.info(s"Collected $datasetRepo")
      datasetRepo.recordDb.db(_.execute(new Optimize()))
      log.info(s"Optimized ${datasetRepo.analyzedDir.getName}.")
      datasetRepo.datasetDb.setStatus(SAVED)
      context.stop(self)

    case SaveRecords() =>
      datasetRepo.datasetDb.setStatus(SAVING)
      datasetRepo.recordDb.db {
        session =>
          session.create(sourceRepo.sourceFile.getName, new BufferedInputStream(new FileInputStream(sourceRepo.sourceFile)))
          session.execute(new Optimize())
      }
      datasetRepo.datasetDb.setStatus(SAVED)
      context.stop(self)
  }
}

