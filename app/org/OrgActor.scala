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

package org

import akka.actor.{Actor, ActorRef, Props, Terminated}
import dataset.DatasetActor
import dataset.DatasetActor.InterruptWork
import org.OrgActor.{DatasetMessage, InterruptDataset}
import org.OrgRepo.repo
import play.api.Logger
import play.libs.Akka

import scala.language.postfixOps

/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

object OrgActor {

  lazy val actor: ActorRef = Akka.system.actorOf(Props[OrgActor], repo.orgId)

  case class DatasetMessage(name: String, message: AnyRef)

  case class InterruptDataset(name: String)

}


class OrgActor extends Actor {

  def receive = {

    case DatasetMessage(name: String, message: AnyRef) =>
      val datasetActor = context.child(name).getOrElse {
        val ref = context.actorOf(DatasetActor.props(repo.datasetRepo(name)))
        Logger.info(s"Created $ref")
        context.watch(ref)
        ref
      }
      datasetActor ! message

    case InterruptDataset(name) =>
      context.child(name).foreach(_ ! InterruptWork())

    case Terminated(name) =>
      Logger.info(s"Demised $name")
      Logger.info(s"Children: ${context.children}")
  }
}



