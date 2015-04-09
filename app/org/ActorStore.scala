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
import play.api.Logger
import triplestore.GraphProperties._
import triplestore.Sparql._
import triplestore.TripleStore

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

object ActorStore {

  val patience = 1.minute

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

  val digest = MessageDigest.getInstance("SHA-256")

  def futureModel = ts.dataGet(actorsGraph).fallbackTo(Future(ModelFactory.createDefaultModel()))

  def getModel = Await.result(futureModel, patience)

  private def propUri(prop: NXProp, m: Model) = m.getProperty(prop.uri)

  private def hashLiteral(password: String, salt: String, model: Model): Literal = {
    digest.reset()
    val salted = password + salt
    val ba = digest.digest(salted.getBytes("UTF-8"))
    val hash = ba.map("%02x".format(_)).mkString
    model.createLiteral(hash)
  }

  private def userIntoModel(actor: NXActor, hashLiteralOpt: Option[Literal], makerOpt: Option[NXActor], model: Model): Option[Model] = {
    val actorResource: Resource = model.getResource(actor.uri)
    val exists = model.listStatements(actorResource, propUri(passwordHash, model), null).hasNext
    if (!exists || !makerOpt.isDefined) {
      model.add(actorResource, model.getProperty(rdfType), model.getResource(actorEntity))
      hashLiteralOpt.map(hashLiteral => model.add(actorResource, propUri(passwordHash, model), hashLiteral))
      makerOpt.map(maker => model.add(actorResource, propUri(actorOwner, model), model.getResource(maker.uri)))
      model.add(actorResource, propUri(username, model), actor.actorName)
      Some(model)
    } else {
      None
    }
  }

  private def getLiteral(nxActor: NXActor, prop: NXProp, model: Model) = {
    val resource: Resource = model.getResource(nxActor.uri)
    val list = model.listObjectsOfProperty(resource, propUri(prop, model))
    list.map(node => node.asLiteral().getString).toList.headOption.getOrElse("")
  }

  private def profileFromModel(actor: NXActor, model: Model) = {
    NXProfile(
      firstName = getLiteral(actor, userFirstName, model),
      lastName = getLiteral(actor, userLastName, model),
      email = getLiteral(actor, userEMail, model)
    )
  }

  def emailFromUri(actorUri: String): Option[String] = {
    val model = getModel
    val resource = model.getResource(actorUri)
    val email = propUri(userEMail, model)
    model.listObjectsOfProperty(resource, email).toList.headOption.map(obj => obj.asLiteral().getString)
  }

//  def oauthAuthenticated(actorName: String): Future[Option[NXActor]] = {
//    val model = getModel
//    if (model.isEmpty) {
//      val godUser = NXActor(actorName, None)
//      userIntoModel(godUser, None, None, model).map { modelWithGod =>
//        val dataPost = ts.up.dataPost(actorsGraph, modelWithGod)
//        checkFail(dataPost)
//        dataPost.map(ok => Some(godUser)).map(ok => Some(godUser))
//      } getOrElse {
//        Future(None)
//      }
//    }
//    else {
//      val actor: NXActor = NXActor(actorName, None)
//      val actorResource: Resource = model.getResource(actor.uri)
//      val usernameResource = model.createLiteral(actorName)
//      val exists = model.listStatements(actorResource, model.getProperty(username.uri), usernameResource).hasNext
//      val userOpt = if (exists) {
//        val makerList = model.listObjectsOfProperty(actorResource, propUri(actorOwner, model)).toList
//        Some(actor.copy(
//          makerOpt = makerList.map(node => node.asResource().getURI).toList.headOption,
//          profileOpt = profileFromModel(actor, model).nonEmpty
//        ))
//      }
//      else {
//        val authenticatedUser = NXActor(actorName, None)
//        // todo: this is god then.  problem!
//        userIntoModel(authenticatedUser, None, None, model).map { modelWithGod =>
//          val dataPost = ts.up.dataPost(actorsGraph, modelWithGod)
//          checkFail(dataPost)
//          dataPost.map(ok => Some(authenticatedUser)).map(ok => Some(authenticatedUser))
//        } getOrElse {
//          Future(None)
//        }
//      }
//      Future(userOpt)
//    }
//  }
//
  def authenticate(actorName: String, password: String): Future[Option[NXActor]] = {
    val model = getModel
    val hash = hashLiteral(password, actorName, model)
    if (model.isEmpty) {
      val godUser = NXActor(actorName, None)
      userIntoModel(godUser, Some(hash), None, model).map { modelWithGod =>
        val dataPost = ts.up.dataPost(actorsGraph, modelWithGod)
        checkFail(dataPost)
        dataPost.map(ok => Some(godUser)).map(ok => Some(godUser))
      } getOrElse {
        Future(None)
      }
    }
    else {
      val actor: NXActor = NXActor(actorName, None)
      val actorResource: Resource = model.getResource(actor.uri)
      val exists = model.listStatements(actorResource, model.getProperty(passwordHash.uri), hash).hasNext
      val userOpt = if (exists) {
        val makerList = model.listObjectsOfProperty(actorResource, propUri(actorOwner, model)).toList
        Some(actor.copy(
          makerOpt = makerList.map(node => node.asResource().getURI).toList.headOption,
          profileOpt = profileFromModel(actor, model).nonEmpty
        ))
      }
      else {
        None
      }
      Future(userOpt)
    }
  }

  def listActors(nxActor: NXActor): List[String] = {
    val model = getModel
    val resource = model.getResource(nxActor.uri)
    val maker = propUri(actorOwner, model)
    val usernameResource = propUri(username, model)
    val list = model.listSubjectsWithProperty(maker, resource).toList.flatMap { madeActorResource =>
      model.listObjectsOfProperty(madeActorResource, usernameResource).toList.headOption.map(_.asLiteral().getString)
    }
    list.toList
  }

  def createActor(adminActor: NXActor, usernameString: String, password: String): Future[Option[NXActor]] = {
    val model = getModel
    val hash = hashLiteral(password, usernameString, model)
    val newActor = NXActor(usernameString, Some(adminActor.uri))
    userIntoModel(newActor, Some(hash), Some(adminActor), model).map { modelWithActorAdded =>
      val dataPost = ts.up.dataPost(actorsGraph, modelWithActorAdded)
      checkFail(dataPost)
      dataPost.map(ok => Some(newActor))
    } getOrElse {
      Future(None)
    }
  }

  def setProfile(actor: NXActor, nxProfile: NXProfile): Future[Unit] = {
    val q = setActorProfileQ(actor, nxProfile)
    val update = ts.up.sparqlUpdate(q)
    checkFail(update)
    update
  }

  def setPassword(actor: NXActor, newPassword: String): Future[Boolean] = {
    val hash = hashLiteral(newPassword, actor.actorName, getModel)
    val update = ts.up.sparqlUpdate(setActorPasswordQ(actor, hash.getString))
    checkFail(update)
    update.map(ok => true)
  }

  private def checkFail(futureUnit: Future[Unit]) = futureUnit.onFailure {
    case e: Throwable =>
      Logger.error("Problem with ActorStore", e)
  }
}
