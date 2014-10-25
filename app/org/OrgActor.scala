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
import akka.pattern.ask
import akka.util.Timeout
import org.OrgActor.{ActorKickoff, ActorShutdown}
import play.api.Logger
import play.libs.Akka

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps
/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

object OrgActor {

  lazy val actor = Akka.system.actorOf(Props[OrgActor], OrgRepo.repo.orgId)

  case class ActorKickoff(props: Props, name: String)

  case class ActorShutdown(name: String)

  implicit val timeout = Timeout(30 seconds)

  def create(props: Props, name: String, receiver: ActorRef => Unit) = {
    val futureActor = actor ? ActorKickoff(props, name)
    futureActor.onFailure {
      case e: Exception =>
        Logger.error(s"Unable to create actor $name")
    }
    futureActor.onSuccess {
      case a: ActorRef =>
        receiver(a)
    }
  }

  def shutdownOr(name: String, whenNoActor: => Unit) = {
    val futureActor = actor ? ActorShutdown(name)
    futureActor.onFailure {
      case e: Exception =>
        Logger.error(s"Unable to send to actor $name")
    }
    futureActor.onSuccess {
      case sent: Boolean =>
        if (!sent) whenNoActor
    }
  }
}


class OrgActor extends Actor {

  def receive = {

    case ActorKickoff(props, name) =>
      sender ! context.child(name).getOrElse {
        val ref = context.actorOf(props, name)
        Logger.info(s"Created $ref")
        context.watch(ref)
        ref
      }

    case ActorShutdown(name) =>
      sender ! context.child(name).exists { ref =>
        ref ! ActorShutdown(name)
        true
      }

    case Terminated(name) =>
      Logger.info(s"Demised $name")
      Logger.info(s"Children: ${context.children}")
  }
}



