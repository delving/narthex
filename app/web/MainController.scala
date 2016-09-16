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

package web

import java.io.File

import org.{AuthenticationService, Profile, User, UserRepository}
import play.api._
import play.api.cache.CacheApi
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.api.mvc._
import play.api.routing.JavaScriptReverseRouter

import scala.concurrent.Future


class MainController(val userRepository: UserRepository,
                     val authenticationService: AuthenticationService,
                     val cacheApi: CacheApi,
                     val apiAccessKeys: List[String],
                     val narthexDomain: String, val naveDomain: String, val orgId: String) extends Controller with Security {

  val SIP_APP_URL = s"http://artifactory.delving.org/artifactory/delving/eu/delving/sip-app/${Utils.SIP_APP_VERSION}/sip-app-${Utils.SIP_APP_VERSION}-exejar.jar"

  def userSession(user: User) = UserSession(
    user,
    apiKey = this.apiAccessKeys.head,  // this is weird but let's not yak-shave too much
    narthexDomain = this.narthexDomain,
    naveDomain = this.naveDomain
  )

  def root = Action { request =>
    Redirect("/narthex/")
  }

  def index = Action { request =>
    Ok(views.html.index(orgId, SIP_APP_URL, buildinfo.BuildInfo.version, buildinfo.BuildInfo.gitCommitSha))
  }

  def login = Action.async(parse.json) { implicit request =>
    val username = (request.body \ "username").as[String]
    val password = (request.body \ "password").as[String]
    Logger.info(s"Login $username")

    val authenticated: Future[Boolean] = authenticationService.authenticate(username, password)

    authenticated.flatMap {
      case false => Future.successful(Unauthorized("Username/password not found"))
      case true => {
        userRepository.loadActor(username).map { actor =>
          val session = userSession(actor)
          Ok(Json.toJson(session)).withSession(session)
        }
      }
    }
  }

  def checkLogin = Action { implicit request =>
    val maybeToken = request.headers.get(TOKEN)
    maybeToken flatMap {
      token =>
        cacheApi.get[UserSession](token) map { session =>
          Ok(Json.toJson(session)).withSession(session)
        }
    } getOrElse Unauthorized(Json.obj("err" -> "Check login failed")).discardingToken(TOKEN)
  }

  /** Logs the user out, i.e. invalidated the token. */
  def logout = Action { implicit request =>
    Logger.info(s"Logout")
    request.headers.get(TOKEN) match {
      case Some(token) =>
        Ok.discardingToken(token)
      case None =>
        Unauthorized(Json.obj("err" -> "Logout failed"))
    }
  }

  def setProfile() = SecureAsync(parse.json) { session => implicit request =>
    val nxProfile = Profile(
      firstName = (request.body \ "firstName").as[String],
      lastName = (request.body \ "lastName").as[String],
      email = (request.body \ "email").as[String]
    )
    val setOp = userRepository.setProfile(session.user, nxProfile).map { ok =>
      val newSession = session.copy(user = session.user.copy(profileOpt = Some(nxProfile)))
      Ok(Json.toJson(newSession)).withSession(newSession)
    }
    setOp.onFailure {
      case e: Throwable =>
        Logger.error("Problem setting profile", e)
    }
    setOp
  }

  def listActors = Secure() { session => implicit request =>
    Ok(Json.obj("actorList" -> userRepository.listSubActors(session.user)))
  }

  def createActor() = SecureAsync(parse.json) { session => implicit request =>
    val username = (request.body \ "username").as[String]
    val password = (request.body \ "password").as[String]
    userRepository.createSubActor(session.user, username, password).map { actorOpt =>
      Ok(Json.obj("actorList" -> userRepository.listSubActors(session.user)))
    }
  }

  def deleteActor() = SecureAsync(parse.json) { session => implicit request =>
    val username = (request.body \ "username").as[String]
    userRepository.deleteActor(username).map { actorOpt =>
      Ok(Json.obj("actorList" -> userRepository.listSubActors(session.user)))
    }
  }

  def disableActor() = SecureAsync(parse.json) { session => implicit request =>
    val username = (request.body \ "username").as[String]
    userRepository.disableActor(username).map { actorOpt =>
      Ok(Json.obj("actorList" -> userRepository.listSubActors(session.user)))
    }
  }

  def enableActor() = SecureAsync(parse.json) { session => implicit request =>
    val username = (request.body \ "username").as[String]
    userRepository.enableActor(username).map { actorOpt =>
      Ok(Json.obj("actorList" -> userRepository.listSubActors(session.user)))
    }
  }

  def makeAdmin() = SecureAsync(parse.json) { session => implicit request =>
    val username = (request.body \ "username").as[String]
    userRepository.makeAdmin(username).map { actorOpt =>
      Ok(Json.obj("actorList" -> userRepository.listSubActors(session.user)))
    }
  }

  def removeAdmin() = SecureAsync(parse.json) { session => implicit request =>
    val username = (request.body \ "username").as[String]
    userRepository.removeAdmin(username).map { actorOpt =>
      Ok(Json.obj("actorList" -> userRepository.listSubActors(session.user)))
    }
  }

  def setPassword() = SecureAsync(parse.json) { session => implicit request =>
    val newPassword = (request.body \ "newPassword").as[String]
    userRepository.setPassword(session.user, newPassword).map(alright => Ok)
  }

  def OkFile(file: File, attempt: Int = 0): Result = Utils.okFile(file, attempt)

  def OkXml(xml: String): Result = Utils.okXml(xml)

  /**
    * Returns the JavaScript router that the client can use for "type-safe" routes.
    */
  def jsRoutes(varName: String = "jsRoutes") = Action { implicit request =>
    Ok(
      JavaScriptReverseRouter(varName)(
        routes.javascript.MainController.login,
        routes.javascript.MainController.checkLogin,
        routes.javascript.MainController.logout,
        routes.javascript.MainController.setProfile,
        routes.javascript.MainController.setPassword,
        routes.javascript.MainController.listActors,
        routes.javascript.MainController.createActor,
        routes.javascript.MainController.disableActor,
        routes.javascript.MainController.enableActor,
        routes.javascript.MainController.deleteActor,
        routes.javascript.MainController.makeAdmin,
        routes.javascript.MainController.removeAdmin,
        routes.javascript.WebSocketController.dataset,
        routes.javascript.AppController.listDatasets,
        routes.javascript.AppController.listPrefixes,
        routes.javascript.AppController.createDataset,
        routes.javascript.AppController.setDatasetProperties,
        routes.javascript.AppController.toggleSkosifiedField,
        routes.javascript.AppController.datasetInfo,
        routes.javascript.AppController.command,
        routes.javascript.AppController.nodeStatus,
        routes.javascript.AppController.index,
        routes.javascript.AppController.sample,
        routes.javascript.AppController.histogram,
        routes.javascript.AppController.setRecordDelimiter,
        routes.javascript.AppController.getTermVocabulary,
        routes.javascript.AppController.getTermMappings,
        routes.javascript.AppController.getCategoryMappings,
        routes.javascript.AppController.toggleTermMapping,
        routes.javascript.AppController.getCategoryList,
        routes.javascript.AppController.listSheets,
        routes.javascript.AppController.gatherCategoryCounts,
        routes.javascript.AppController.listSipFiles,
        routes.javascript.AppController.deleteLatestSipFile,
        routes.javascript.AppController.listVocabularies,
        routes.javascript.AppController.createVocabulary,
        routes.javascript.AppController.deleteVocabulary,
        routes.javascript.AppController.setVocabularyProperties,
        routes.javascript.AppController.vocabularyInfo,
        routes.javascript.AppController.vocabularyStatistics,
        routes.javascript.AppController.getSkosMappings,
        routes.javascript.AppController.toggleSkosMapping,
        routes.javascript.AppController.searchVocabulary,
        routes.javascript.AppController.getVocabularyLanguages
      )
    ).as(JAVASCRIPT)
  }
}
