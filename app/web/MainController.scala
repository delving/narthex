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

import controllers.WebJarAssets
import org.webjars.play.RequireJS
import org.{AuthenticationService, Profile, User, UserRepository}
import play.api._
import play.api.cache.{CacheApi, Cached}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.Future
import scala.concurrent.duration._


class MainController(val userRepository: UserRepository,
                     val authenticationService: AuthenticationService,
                     val cacheApi: CacheApi,
                     val apiAccessKeys: List[String],
                     val narthexDomain: String, val naveDomain: String, val orgId: String,
                     val webJarAssets: WebJarAssets, val requireJS: RequireJS, val sessionTimeoutInSeconds: Int
                    ) extends Controller with Security {

  val cacheDuration = 1.day

  /**
    * Caching action that caches an OK response for the given amount of time with the key.
    * NotFound will be cached for 5 mins. Any other status will not be cached.
    */
  def Caching(key: String, okDuration: Duration) = {
    new Cached(cacheApi)
      .status(_ => key, OK, okDuration.toSeconds.toInt)
      .includeStatus(NOT_FOUND, 5.minutes.toSeconds.toInt)
  }

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
    Ok(views.html.index(orgId, SIP_APP_URL, buildinfo.BuildInfo.version,
      buildinfo.BuildInfo.gitCommitSha, webJarAssets, requireJS)).withHeaders(
      CACHE_CONTROL -> "no-cache")
  }

  def login = Action.async(parse.json) { implicit request =>
    val username = (request.body \ "username").as[String]
    val password = (request.body \ "password").as[String]
    Logger.debug(s"Login $username")

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
    Logger.debug(s"Logout")
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
    * Retrieves all routes via reflection.
    * http://stackoverflow.com/questions/12012703/less-verbose-way-of-generating-play-2s-javascript-router
    * @todo If you have controllers in multiple packages, you need to add each package here.
    */
  val routeCache = {
    val jsRoutesClasses = Seq(classOf[routes.javascript]) // TODO add your own packages
    jsRoutesClasses.flatMap { jsRoutesClass =>
      val controllers = jsRoutesClass.getFields.map(_.get(null))
      controllers.flatMap { controller =>
        controller.getClass.getDeclaredMethods.filter(_.getName != "_defaultPrefix").map { action =>
          action.invoke(controller).asInstanceOf[play.api.routing.JavaScriptReverseRoute]
        }
      }
    }
  }

  /**
    * Returns the JavaScript router that the client can use for "type-safe" routes.
    * Uses browser caching; set duration (in seconds) according to your release cycle.
    * @param varName The name of the global variable, defaults to `jsRoutes`
    */
  def jsRoutes(varName: String = "jsRoutes") = Caching("jsRoutes", cacheDuration) {
    Action { implicit request =>
      Ok(play.api.routing.JavaScriptReverseRouter(varName)(routeCache: _*)).as(JAVASCRIPT)
    }
  }

}
