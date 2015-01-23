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
import triplestore.TripleStore
import triplestore.TripleStore._

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object UserStore {

  val graphName = s"${NX_NAMESPACE}Users"

  case class NXProfile(firstName: String, lastName: String, email: String)

  case class NXActor(userNameProposed: String, makerOpt: Option[String], profileOpt: Option[NXProfile] = None) {
    val actorName = userNameProposed.replaceAll("[^\\w-]", "").toLowerCase
    val uri = s"$NX_URI_PREFIX/actor/$actorName"
  }

  case class USProp(name: String, dataType: PropType = stringProp) {
    val uri = s"$NX_NAMESPACE$name"
  }

  val username = USProp("username")
  val passwordHash = USProp("passwordHash")
  val userEMail = USProp("userEMail")
  val userFirstName = USProp("userFirstName")
  val userLastName = USProp("userLastName")
  val userMaker = USProp("userMaker")

  lazy val us = new UserStore(TripleStore.ts)

}

class UserStore(client: TripleStore) {

  import org.UserStore._

  val digest = MessageDigest.getInstance("SHA-256")

  val futureModel = client.dataGet(graphName).fallbackTo(Future(ModelFactory.createDefaultModel()))

  val model = Await.result(futureModel, 20.seconds)

  private def hashLiteral(string: String): Literal = {
    digest.reset()
    val ba = digest.digest(string.getBytes("UTF-8"))
    val hash = ba.map("%02x".format(_)).mkString
    model.createLiteral(hash)
  }

  private def userIntoModel(nxActor: NXActor, hashLiteral: Literal): Option[NXActor] = {
    val actorResource: Resource = model.getResource(nxActor.uri)
    val exists = model.listStatements(actorResource, model.getProperty(passwordHash.uri), null).hasNext
    if (!exists) {
      model.add(actorResource, model.getProperty(passwordHash.uri), hashLiteral)
      model.add(actorResource, model.getProperty(username.uri), nxActor.actorName)
      // model.add(actorResource, model.getProperty(userRole.uri), model.createLiteral(ADMINISTRATOR_ROLE))
      Some(nxActor)
    } else None
  }

  private def profileIntoModel(nxActor: NXActor, nxProfile: NXProfile): Option[NXActor] = {
    val actorResource: Resource = model.getResource(nxActor.uri)
    val exists = model.listStatements(actorResource, model.getProperty(passwordHash.uri), null).hasNext
    if (exists) {
      model.add(actorResource, model.getProperty(userFirstName.uri), nxProfile.firstName)
      model.add(actorResource, model.getProperty(userLastName.uri), nxProfile.lastName)
      model.add(actorResource, model.getProperty(userEMail.uri), nxProfile.email)
      Some(nxActor.copy(profileOpt = Some(nxProfile)))
    } else None
  }

  private def getLiteral(nxActor: NXActor, prop: USProp) = {
    val resource: Resource = model.getResource(nxActor.uri)
    val list = model.listObjectsOfProperty(resource, model.getProperty(prop.uri))
    list.map(node => node.asLiteral().getString).toList.headOption.getOrElse("")
  }

  private def profileFromModel(nxActor: NXActor) = NXProfile(
    firstName = getLiteral(nxActor, userFirstName),
    lastName = getLiteral(nxActor, userLastName),
    email = getLiteral(nxActor, userEMail)
  )

  def authenticate(usernameString: String, password: String): Future[Option[NXActor]] = futureModel.flatMap { newModel =>
    val hash = hashLiteral(usernameString + password)
    if (newModel.isEmpty) {
      val godUser = NXActor(usernameString, None)
      userIntoModel(godUser, hash)
      client.dataPost(graphName, newModel).map(ok => Some(godUser))
    }
    else {
      val nxActor: NXActor = NXActor(usernameString, None)
      val nxActorResource: Resource = newModel.getResource(nxActor.uri)
      val exists = newModel.listStatements(nxActorResource, newModel.getProperty(passwordHash.uri), hash).hasNext
      val userOpt = if (exists) {
        val makerList = newModel.listObjectsOfProperty(nxActorResource, newModel.getProperty(userMaker.uri))
        val makerOpt = makerList.map(node => node.asResource().getURI).toList.headOption
        Some(nxActor.copy(makerOpt = makerOpt, profileOpt = Some(profileFromModel(nxActor))))
      }
      else {
        None
      }
      Future(userOpt)
    }
  }

  def createActor(adminActor: NXActor, usernameString: String, password: String): Future[Option[NXActor]] = {
    val hash = hashLiteral(usernameString + password)
    userIntoModel(NXActor(usernameString, Some(adminActor.uri)), hash).map { user: NXActor =>
      val sparql =
        s"""
         |DELETE {
         |   GRAPH <$graphName> {
         |      <${user.uri}> <${model.getProperty(username.uri)}> ?userName .
         |      <${user.uri}> <${model.getProperty(passwordHash.uri)}> ?passwordHash .
         |   }
         |}
         |INSERT {
         |   GRAPH <$graphName> {
         |      <${user.uri}> <${model.getProperty(username.uri)}> "${user.actorName}" .
         |      <${user.uri}> <${model.getProperty(passwordHash.uri)}> "${hash.getString}" .
         |   }
         |}
         |WHERE {
         |   GRAPH <$graphName> {
         |      <${user.uri}> <${model.getProperty(username.uri)}> ?username .
         |      <${user.uri}> <${model.getProperty(passwordHash.uri)}> ?passwordHash .
         |   }
         |}
       """.stripMargin
      client.update(sparql).map(ok => Some(user))
    } getOrElse Future(None)
  }

  def setProfile(nxActor: NXActor, nxProfile: NXProfile): Future[Option[NXActor]] = {
    println(s"set profile $nxProfile")
    profileIntoModel(nxActor, nxProfile).map { actor =>
      val sparql =
        s"""
         |DELETE {
         |   GRAPH <$graphName> {
         |      <${nxActor.uri}> <${model.getProperty(userFirstName.uri)}> ?firstName .
         |      <${nxActor.uri}> <${model.getProperty(userLastName.uri)}> ?lastName .
         |      <${nxActor.uri}> <${model.getProperty(userEMail.uri)}> ?email .
         |   }
         |}
         |INSERT {
         |   GRAPH <$graphName> {
         |      <${nxActor.uri}> <${model.getProperty(userFirstName.uri)}> "${nxProfile.firstName}" .
         |      <${nxActor.uri}> <${model.getProperty(userLastName.uri)}> "${nxProfile.lastName}" .
         |      <${nxActor.uri}> <${model.getProperty(userEMail.uri)}> "${nxProfile.email}" .
         |   }
         |}
         |WHERE {
         |   GRAPH <$graphName> {
         |      <${nxActor.uri}> <${model.getProperty(userFirstName.uri)}> ?firstName .
         |      <${nxActor.uri}> <${model.getProperty(userLastName.uri)}> ?lastName .
         |      <${nxActor.uri}> <${model.getProperty(userEMail.uri)}> ?email .
         |   }
         |}
       """.stripMargin
      client.update(sparql).map(ok => Some(actor))
    } getOrElse {
      println(s"profile did not go to model $nxProfile")
      Future(None)
    }
  }

  def setActive(usernameString: String, active: Boolean): Unit = {
    // todo: implement
  }

}
