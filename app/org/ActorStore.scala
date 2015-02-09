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

import java.security.MessageDigest

import com.hp.hpl.jena.rdf.model._
import org.OrgContext._
import triplestore.GraphProperties._
import triplestore.Sparql._
import triplestore.TripleStore

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

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

class ActorStore(ts: TripleStore) {

  import org.ActorStore._

  val digest = MessageDigest.getInstance("SHA-256")

  val futureModel = ts.dataGet(actorsGraph).fallbackTo(Future(ModelFactory.createDefaultModel()))

  val model = Await.result(futureModel, 20.seconds)

  private def propUri(prop: NXProp) = model.getProperty(prop.uri)

  private def hashLiteral(password: String, salt: String): Literal = {
    digest.reset()
    val salted = password + salt
    val ba = digest.digest(salted.getBytes("UTF-8"))
    val hash = ba.map("%02x".format(_)).mkString
    model.createLiteral(hash)
  }

  private def userIntoModel(actor: NXActor, hashLiteral: Literal, makerOpt: Option[NXActor]): Option[NXActor] = {
    val actorResource: Resource = model.getResource(actor.uri)
    val exists = model.listStatements(actorResource, propUri(passwordHash), null).hasNext
    if (!exists) {
      model.add(actorResource, model.getProperty(rdfType), model.getResource(actorEntity))
      model.add(actorResource, propUri(passwordHash), hashLiteral)
      makerOpt.map(maker => model.add(actorResource, propUri(actorOwner), model.getResource(maker.uri)))
      model.add(actorResource, propUri(username), actor.actorName)
      Some(actor)
    } else {
      None
    }
  }

  private def profileIntoModel(nxActor: NXActor, nxProfile: NXProfile): Option[NXActor] = {
    val actorResource: Resource = model.getResource(nxActor.uri)
    val exists = model.listStatements(actorResource, propUri(passwordHash), null).hasNext
    if (exists) {
      model.add(actorResource, propUri(userFirstName), nxProfile.firstName)
      model.add(actorResource, propUri(userLastName), nxProfile.lastName)
      model.add(actorResource, propUri(userEMail), nxProfile.email)
      Some(nxActor.copy(profileOpt = nxProfile.nonEmpty))
    } else None
  }

  private def getLiteral(nxActor: NXActor, prop: NXProp) = {
    val resource: Resource = model.getResource(nxActor.uri)
    val list = model.listObjectsOfProperty(resource, propUri(prop))
    list.map(node => node.asLiteral().getString).toList.headOption.getOrElse("")
  }

  private def profileFromModel(actor: NXActor) = NXProfile(
    firstName = getLiteral(actor, userFirstName),
    lastName = getLiteral(actor, userLastName),
    email = getLiteral(actor, userEMail)
  )

  def authenticate(actorName: String, password: String): Future[Option[NXActor]] = {
    val hash = hashLiteral(password, actorName)
    if (model.isEmpty) {
      val godUser = NXActor(actorName, None)
      userIntoModel(godUser, hash, None)
      ts.dataPost(actorsGraph, model).map(ok => Some(godUser))
    }
    else {
      val actor: NXActor = NXActor(actorName, None)
      val actorResource: Resource = model.getResource(actor.uri)
      val exists = model.listStatements(actorResource, model.getProperty(passwordHash.uri), hash).hasNext
      val userOpt = if (exists) {
        val makerList = model.listObjectsOfProperty(actorResource, propUri(actorOwner)).toList
        Some(actor.copy(
          makerOpt = makerList.map(node => node.asResource().getURI).toList.headOption,
          profileOpt = profileFromModel(actor).nonEmpty
        ))
      }
      else {
        None
      }
      Future(userOpt)
    }
  }

  def listActors(nxActor: NXActor): List[String] = {
    val resource = model.getResource(nxActor.uri)
    val maker = propUri(actorOwner)
    val usernameResource = propUri(username)
    val list = model.listSubjectsWithProperty(maker, resource).toList.flatMap { madeActorResource =>
      model.listObjectsOfProperty(madeActorResource, usernameResource).toList.headOption.map(_.asLiteral().getString)
    }
    list.toList
  }

  def createActor(adminActor: NXActor, usernameString: String, password: String): Future[Option[NXActor]] = {
    val hash = hashLiteral(password, usernameString)
    val newActor = NXActor(usernameString, Some(adminActor.uri))
    userIntoModel(newActor, hash, Some(adminActor)).map { actor: NXActor =>
      ts.update(insertActorQ(newActor, hash.getString, adminActor)).map(ok => Some(actor))
    } getOrElse {
      Future(None)
    }
  }

  def setProfile(actor: NXActor, nxProfile: NXProfile): Future[Option[NXActor]] = {
    profileIntoModel(actor, nxProfile).map { actor =>
      ts.update(setActorProfileQ(actor, nxProfile)).map(ok => Some(actor))
    } getOrElse {
      Future(None)
    }
  }

  def setPassword(actor: NXActor, newPassword: String) = {
    val actorResource: Resource = model.getResource(actor.uri)
    val prop = model.getProperty(passwordHash.uri)
    val hash = hashLiteral(newPassword, actor.actorName)
    model.removeAll(actorResource, prop, null)
    model.add(actorResource, prop, hash)
    ts.update(setActorPasswordQ(actor, hash.getString)).map(errorOpt => true)
  }
}
