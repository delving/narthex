//===========================================================================
//    Copyright 2015 Delving B.V.
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

import java.io.Serializable
import java.security.MessageDigest

import org.OrgContext._
import play.api.Logger
import triplestore.GraphProperties._
import triplestore.Sparql._
import triplestore.TripleStore

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

object ActorStore {

  case class NXProfile(firstName: String, lastName: String, email: String) {
    val nonEmpty = if (firstName.isEmpty && lastName.isEmpty && email.isEmpty) None else Some(this)
  }

  case class NXActor(userNameProposed: String, makerOpt: Option[String], profileOpt: Option[NXProfile] = None) {
    val actorName = userNameProposed.replaceAll("[^\\w-]", "").toLowerCase
    val uri = s"$NX_URI_PREFIX/actor/$actorName"

    override def toString = uri
  }

}

class ActorStore()(implicit ec: ExecutionContext, ts: TripleStore) {

  import org.ActorStore._

  private def hashPassword(password: String, salt: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val salted = password + salt
    val ba = digest.digest(salted.getBytes("UTF-8"))
    ba.map("%02x".format(_)).mkString
  }

  def emailFromUri(actorUri: String): Option[String] = {
    //val query = ts.query(getEMailOfActor(actorUri)).map(emailFromResult)
    // send email to all admins
    val query = ts.query(getAdminEMailQ()).map(emailFromResult)
    // todo: maybe make this async
    Await.result(query, 5.seconds)
  }

  def oAuthenticated(actorName: String): Future[NXActor] = {
    ts.query(getActor(actorName)).map(actorFromResult).flatMap { actorOpt =>
      actorOpt.map(Future.successful).getOrElse {
        val newActor = NXActor(actorName, None)
        ts.up.sparqlUpdate(insertOAuthActorQ(newActor)).map(ok => newActor)
      }
    }
  }

  def authenticate(actorName: String, password: String): Future[Option[NXActor]] = {
    val passwordHashString = hashPassword(password, actorName)
    ts.query(getActorWithPassword(actorName, passwordHashString)).map(actorFromResult).flatMap { actorOpt =>
      if (actorOpt.isEmpty) {
        ts.ask(graphExistsQ(actorsGraph)).flatMap { exists =>
          if (exists) {
            Future.successful(None)
          }
          else {
            val topActor = NXActor(actorName, None)
            ts.up.sparqlUpdate(insertTopActorQ(topActor, passwordHashString)).map(ok => Some(topActor))
          }
        }
      }
      else {
        Future.successful(actorOpt)
      }
    }
  }

  def listSubActors(nxActor: NXActor): List[Map[String, String]] = {
    val query = ts.query(getSubActorList(nxActor)).map { list =>
//      list.flatMap(m => m.get("username")).map(_.text)
        list.map(m =>
          Map(
            "userName" -> m.get("username").get.text,
            "isAdmin" -> {
              val isAdmin = m.get("isAdmin")
              if (isAdmin.nonEmpty) isAdmin.get.text else "false"},
            "userEnabled" -> {
              val userEnabled = m.get("userEnabled")
              if (userEnabled.nonEmpty) userEnabled.get.text else "true"
            }
          )
        )
    }
    // todo: maybe make this async
    Await.result(query, 5.seconds)
  }

  def createSubActor(adminActor: NXActor, usernameString: String, password: String): Future[Option[NXActor]] = {
    val hash = hashPassword(password, usernameString)
    val newActor = NXActor(usernameString, Some(adminActor.uri))
    val update = ts.up.sparqlUpdate(insertSubActorQ(newActor, hash, adminActor))
    checkFail(update)
    update.map(ok => Some(newActor))
  }

  def makeAdmin(userName: String) = {
    val actor = NXActor(userName, None, None)
    val q = setActorAdminQ(actor, true)
    val update = ts.up.sparqlUpdate(q)
    checkFail(update)
    update
  }

  def removeAdmin(userName: String) = {
    val actor = NXActor(userName, None, None)
    val q = setActorAdminQ(actor, true)
    val update = ts.up.sparqlUpdate(q)
    checkFail(update)
    update
  }

  def deleteActor(userName: String) = {
    val actor = NXActor(userName, None, None)
    val q = removeActorQ(actor)
    val update = ts.up.sparqlUpdate(q)
    checkFail(update)
    update
  }

  def disableActor(userName: String) = {
    val actor = NXActor(userName, None, None)
    val q = enableActorQ(actor, false)
    val update = ts.up.sparqlUpdate(q)
    checkFail(update)
    update
  }

  def enableActor(userName: String) = {
    val actor = NXActor(userName, None, None)
    val q = enableActorQ(actor, true)
    val update = ts.up.sparqlUpdate(q)
    checkFail(update)
    update
  }

  def setProfile(actor: NXActor, nxProfile: NXProfile): Future[Unit] = {
    val q = setActorProfileQ(actor, nxProfile)
    val update = ts.up.sparqlUpdate(q)
    checkFail(update)
    update
  }

  def setPassword(actor: NXActor, newPassword: String): Future[Boolean] = {
    val hash = hashPassword(newPassword, actor.actorName)
    val update = ts.up.sparqlUpdate(setActorPasswordQ(actor, hash))
    checkFail(update)
    update.map(ok => true)
  }

  private def checkFail(futureUnit: Future[Unit]) = futureUnit.onFailure {
    case e: Throwable =>
      Logger.error("Problem with ActorStore", e)
  }
}
