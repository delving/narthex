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
import services.NarthexConfig._
import triplestore.GraphProperties._
import triplestore.TripleStore

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object ActorStore {

  val graphName = s"${NX_NAMESPACE}Actors"

  case class NXProfile(firstName: String, lastName: String, email: String) {
    val nonEmpty = if (firstName.isEmpty && lastName.isEmpty && email.isEmpty) None else Some(this)
  }

  case class NXActor(userNameProposed: String, makerOpt: Option[String], profileOpt: Option[NXProfile] = None) {
    val actorName = userNameProposed.replaceAll("[^\\w-]", "").toLowerCase
    val uri = s"$NX_URI_PREFIX/actor/$actorName"
    override def toString = uri
  }

}

class ActorStore(client: TripleStore) {

  import org.ActorStore._

  val digest = MessageDigest.getInstance("SHA-256")

  val futureModel = client.dataGet(graphName).fallbackTo(Future(ModelFactory.createDefaultModel()))

  val model = Await.result(futureModel, 20.seconds)

  private def propUri(prop: NXProp)= model.getProperty(prop.uri)

  private def hashLiteral(string: String): Literal = {
    digest.reset()
    val ba = digest.digest(string.getBytes("UTF-8"))
    val hash = ba.map("%02x".format(_)).mkString
    model.createLiteral(hash)
  }

  private def userIntoModel(nxActor: NXActor, hashLiteral: Literal, makerOpt: Option[NXActor]): Option[NXActor] = {
    val actorResource: Resource = model.getResource(nxActor.uri)
    val exists = model.listStatements(actorResource, propUri(passwordHash), null).hasNext
    if (!exists) {
      model.add(actorResource, propUri(passwordHash), hashLiteral)
      makerOpt.map(maker => model.add(actorResource, propUri(actorOwner), model.getResource(maker.uri)))
      model.add(actorResource, propUri(username), nxActor.actorName)
      Some(nxActor)
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

  private def profileFromModel(nxActor: NXActor) = NXProfile(
    firstName = getLiteral(nxActor, userFirstName),
    lastName = getLiteral(nxActor, userLastName),
    email = getLiteral(nxActor, userEMail)
  )
  
  def authenticate(usernameString: String, password: String): Future[Option[NXActor]] = {
    val hash = hashLiteral(usernameString + password)
    if (model.isEmpty) {
      val godUser = NXActor(usernameString, None)
      userIntoModel(godUser, hash, None)
      client.dataPost(graphName, model).map(ok => Some(godUser))
    }
    else {
      val actor: NXActor = NXActor(usernameString, None)
      val actorResource: Resource = model.getResource(actor.uri)
      val exists = model.listStatements(actorResource, model.getProperty(passwordHash.uri), hash).hasNext
      val userOpt = if (exists) {
        val makerList = model.listObjectsOfProperty(actorResource, propUri(actorOwner)).toList
        val makerOpt = makerList.map(node => node.asResource().getURI).toList.headOption
        Some(actor.copy(makerOpt = makerOpt, profileOpt = profileFromModel(actor).nonEmpty))
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
    val hash = hashLiteral(usernameString + password)
    userIntoModel(NXActor(usernameString, Some(adminActor.uri)), hash, Some(adminActor)).map { user: NXActor =>
      val sparql =
        s"""
         |WITH <$graphName>
         |DELETE {
         |   <${user.uri}>
         |      <$username> ?userName ;
         |      <$actorOwner> ?userMaker ;
         |      <$passwordHash> ?passwordHash .
         |}
         |INSERT {
         |   <${user.uri}>
         |      <$username> "${user.actorName}" ;
         |      <$actorOwner> <${adminActor.uri}> ;
         |      <$passwordHash> "${hash.getString}" .
         |}
         |WHERE {
         |   OPTIONAL {
         |      <${user.uri}>
         |         <$username> ?username ;
         |         <$actorOwner> ?userMaker ;
         |         <$passwordHash> ?passwordHash .
         |   }
         |}
       """.stripMargin
      client.update(sparql).map(ok => Some(user))
    } getOrElse {
      Future(None)
    }
  }

  def setProfile(nxActor: NXActor, nxProfile: NXProfile): Future[Option[NXActor]] = {
    profileIntoModel(nxActor, nxProfile).map { actor =>
      val sparql =
        s"""
         |WITH <$graphName>
         |DELETE {
         |   <${nxActor.uri}>
         |      <$userFirstName> ?firstName ;
         |      <$userLastName> ?lastName ;
         |      <$userEMail> ?email .
         |}
         |INSERT {
         |   <${nxActor.uri}>
         |      <$userFirstName> "${nxProfile.firstName}" ;
         |      <$userLastName> "${nxProfile.lastName}" ;
         |      <$userEMail> "${nxProfile.email}" .
         |}
         |WHERE {
         |   <${nxActor.uri}>
         |      <$userFirstName> ?firstName ;
         |      <$userLastName> ?lastName ;
         |      <$userEMail> ?email .
         |}
       """.stripMargin
      client.update(sparql).map(ok => Some(actor))
    } getOrElse {
      Future(None)
    }
  }

  def setActive(usernameString: String, active: Boolean): Unit = {
    // todo: implement
  }

}
