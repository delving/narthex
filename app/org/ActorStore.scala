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

import nxutil.Utils
import play.api.Logger
import triplestore.Sparql._
import triplestore.{Sparql, TripleStore}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class ActorStore(val authenticationService: AuthenticationService, val uriPrefix: String)(implicit ec: ExecutionContext, ts: TripleStore) extends UserRepository {

  val topActorUsername = "admin"

  override def insertAdmin(passwd: String): Future[User] = {
    val topActor = User(topActorUsername, None)
    ts.up.sparqlUpdate(insertTopActorQ(topActor, uriPrefix, Utils.hashPasswordUnsecure(passwd, topActorUsername))).
      map{ ok =>
        Logger.info(s"Created initial admin user")
        topActor
      }
  }

  override def hasAdmin: Future[Boolean] = {
   ts.query(getActor(topActorUsername)).map(m => actorFromResult(m).isDefined)
  }

  /**
    * Retrieve a an actor known to exist
    * @param name the username
    * @return
    * @throws IllegalArgumentException if the actor does not exist
    */
  override def loadActor(name: String): Future[User] = {
    val result: Future[Option[User]] = ts.query(Sparql.getActor(name)).map(actorFromResult)
    result.map { someActor: Option[User] =>
      someActor.getOrElse(throw new IllegalArgumentException(s"No such user $name"))
    }
  }

  override def emailFromUri(actorUri: String): Future[Option[String]] = {
    ts.query(getEMailOfActor(actorUri)).map(emailFromResult)
  }

  override def adminEmails = {
    val sparqlQuery = getAdminEMailQ()
    ts.query(sparqlQuery).map(emailsFromResult)
  }

  override def listSubActors(nxActor: User): List[Map[String, String]] = {
    val query = ts.query(getSubActorList(nxActor)).map { list =>
      list.map(m =>
        Map(
          "userName" -> m("username").text,
          "isAdmin" -> {
            val isAdmin = m.get("isAdmin")
            if (isAdmin.nonEmpty) isAdmin.get.text else "false"
          },
          "userEnabled" -> {
            val actorEnabled = m.get("actorEnabled")
            if (actorEnabled.nonEmpty) actorEnabled.get.text else "true"
          }
        )
      )
    }
    // todo: maybe make this async
    Await.result(query, 5.seconds)
  }

  override def createSubActor(adminActor: User, usernameString: String, password: String): Future[Option[User]] = {
    val hash = Utils.hashPasswordUnsecure(password, usernameString)
    val newActor = User(usernameString, Some(adminActor.uri(uriPrefix)))
    val update = ts.up.sparqlUpdate(insertSubActorQ(newActor, uriPrefix, hash, adminActor))
    checkFail(update)
    update.map(ok => Some(newActor))
  }

  override def makeAdmin(userName: String): Future[Option[User]] = {
    val actor = User(userName, None, None)
    val q = setActorAdminQ(actor, uriPrefix, isAdminToggle = true)
    val update = ts.up.sparqlUpdate(q)
    checkFail(update)
    update.map(ok => Some(actor))
  }

  override def removeAdmin(userName: String): Future[Option[User]] = {
    val actor = User(userName, None, None)
    val q = setActorAdminQ(actor, uriPrefix, isAdminToggle = false)
    val update = ts.up.sparqlUpdate(q)
    checkFail(update)
    update.map(ok => Some(actor))
  }

  override def deleteActor(userName: String): Future[Option[User]] = {
    val actor = User(userName, None, None)
    val q = removeActorQ(actor, uriPrefix)
    val update = ts.up.sparqlUpdate(q)
    checkFail(update)
    update.map(ok => Some(actor))
  }

  override def disableActor(userName: String): Future[Option[User]] = {
    val actor = User(userName, None, None)
    val q = enableActorQ(actor, uriPrefix, false)
    val update = ts.up.sparqlUpdate(q)
    checkFail(update)
    update.map(ok => Some(actor))
  }

  override def enableActor(userName: String): Future[Option[User]] = {
    val actor = User(userName, None, None)
    val q = enableActorQ(actor, uriPrefix, true)
    val update = ts.up.sparqlUpdate(q)
    checkFail(update)
    update.map(ok => Some(actor))
  }

  override def setProfile(actor: User, nxProfile: Profile): Future[Unit] = {
    val q = setActorProfileQ(actor, uriPrefix, nxProfile)
    val update = ts.up.sparqlUpdate(q)
    checkFail(update)
    update
  }

  override def setPassword(actor: User, newPassword: String): Future[Boolean] = {
    val hash = Utils.hashPasswordUnsecure(newPassword, actor.actorName)
    val update = ts.up.sparqlUpdate(setActorPasswordQ(actor, uriPrefix, hash))
    checkFail(update)
    update.map(ok => true)
  }

  private def checkFail(futureUnit: Future[Unit]) = futureUnit.onFailure {
    case e: Throwable =>
      Logger.error("Problem with ActorStore", e)
  }
}
