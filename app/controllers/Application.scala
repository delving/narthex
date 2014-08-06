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

package controllers

import java.io.{File, FileInputStream, FileNotFoundException}

import play.api.Play.current
import play.api._
import play.api.cache.Cache
import play.api.libs.iteratee.Enumerator
import play.api.libs.json._
import play.api.mvc._
import services.{CommonsServices, UserProfile}

import scala.concurrent.ExecutionContext.Implicits.global

object Application extends Controller with Security {

  val maybeOrgId: Option[String] = Play.current.configuration.getString("commons.orgId")

  def index = Action {
    val orgId = maybeOrgId.getOrElse(throw new RuntimeException("Missing config: commons.orgId"))
    val services: CommonsServices = CommonsServices.services
    val orgName = services.getName(orgId, "en").getOrElse(orgId)
    Ok(views.html.index(orgName))
  }

  def login = Action(parse.json) {
    request =>

      def getOrCreateUser(username: String, profileMaybe: Option[UserProfile]): SimpleResult = {
        val profile = profileMaybe.getOrElse(throw new RuntimeException(s"no profile for $username"))
        Logger.warn("get or create " + username + " " + profile)
        val token = java.util.UUID.randomUUID().toString
        // todo: put the user in the database
        Ok(Json.obj(
          "firstName" -> profile.firstName,
          "lastName" -> profile.lastName,
          "email" -> profile.email
        )).withToken(token, profile.email)
      }

      val services: CommonsServices = CommonsServices.services
      var username = (request.body \ "username").as[String]
      var password = (request.body \ "password").as[String]

      Logger.info(s"connecting user $username")
      if (services.connect(username, password)) {
        val orgId: String = maybeOrgId.get
        if (services.isAdmin(orgId, username)) {
          Logger.info(s"Logged in $username of $orgId")
          getOrCreateUser(username, services.getUserProfile(username))
        }
        else {
          Unauthorized(s"User $username is not an admin of organization $orgId")
        }
      }
      else {
        Unauthorized("Username password combination failed")
      }
  }

  def checkLogin = Action {
    implicit request =>
      val maybeToken = request.headers.get(TOKEN)
      maybeToken flatMap {
        token =>
          Cache.getAs[String](token) map {
            email =>
              Ok(Json.obj("email" -> email)).withToken(token, email) // todo: there's more in the profile
          }
      } getOrElse {
        Unauthorized(Json.obj("err" -> "No Token during check login")).discardingToken(TOKEN)
      }
  }

  /** Logs the user out, i.e. invalidated the token. */
  def logout = Action {
    implicit request =>
      request.headers.get(TOKEN) match {
        case Some(token) =>
          Ok.discardingToken(token)
        case None =>
          Unauthorized(Json.obj("err" -> "No Token during logout attempt"))
      }
  }

  def OkFile(file: File, attempt: Int = 0): SimpleResult = {
    try {
      val input = new FileInputStream(file)
      val resourceData = Enumerator.fromStream(input)
      val contentType = if (file.getName.endsWith(".json")) "application/json" else "text/plain; charset=utf-8"
      SimpleResult(
        ResponseHeader(OK, Map(
          CONTENT_LENGTH -> file.length().toString,
          CONTENT_TYPE -> contentType
        )),
        resourceData
      )
    }
    catch {
      case ex: FileNotFoundException if attempt < 5 => // sometimes status files are in the process of being written
        Thread.sleep(333)
        OkFile(file, attempt + 1)
      case x: Throwable =>
        NotFound(Json.obj("file" -> file.getName, "message" -> x.getMessage))
    }
  }


  /**
   * Returns the JavaScript router that the client can use for "type-safe" routes.
   * @param varName The name of the global variable, defaults to `jsRoutes`
   */
  def jsRoutes(varName: String = "jsRoutes") = Action {
    implicit request =>
      Ok(// IntelliJ Idea shows errors here but it compiles:
        Routes.javascriptRouter(varName)(
          routes.javascript.Application.login,
          routes.javascript.Application.checkLogin,
          routes.javascript.Application.logout,
          routes.javascript.Dashboard.list,
          routes.javascript.Dashboard.work,
          routes.javascript.Dashboard.datasetInfo,
          routes.javascript.Dashboard.deleteDataset,
          routes.javascript.Dashboard.nodeStatus,
          routes.javascript.Dashboard.index,
          routes.javascript.Dashboard.sample,
          routes.javascript.Dashboard.histogram,
          routes.javascript.Dashboard.setRecordDelimiter,
          routes.javascript.Dashboard.saveRecords,
          routes.javascript.Dashboard.queryRecords,
          routes.javascript.Dashboard.listSkos,
          routes.javascript.Dashboard.searchSkos,
          routes.javascript.Dashboard.getMappings,
          routes.javascript.Dashboard.setMapping,
          routes.javascript.Dashboard.listSipFiles
        )
      ).as(JAVASCRIPT)
  }
}
