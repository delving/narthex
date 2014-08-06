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
import play.api.libs.Crypto
import play.api.libs.iteratee.Enumerator
import play.api.libs.json._
import play.api.libs.ws.{Response, WS}
import play.api.mvc._
import play.mvc.Http
import services.MissingLibs

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object Application extends Controller with Security {

  def index = Action {
    Ok(views.html.index())
  }

  def getOrCreateUser(username: String, profile: JsValue): Future[SimpleResult] = {
    println("get or create " + username + " " + profile)
    val isPublic = (profile \ "isPublic").as[Boolean]
    val firstName = (profile \ "firstName").as[String]
    val lastName = (profile \ "lastName").as[String]
    val email = (profile \ "email").as[String]
    val token = java.util.UUID.randomUUID().toString
    // todo: put the user in the database
    Future(Ok(Json.obj("user" -> Json.obj("email" -> email))).withToken(token, email))
  }

  def commonsRequest(path: String): Future[Response] = {
    def string(name: String) = Play.current.configuration.getString(name).getOrElse(throw new RuntimeException(s"Missing config: $name"))
    val host: String = string("commons.host")
    val apiToken: String = string("commons.apiToken")
    val orgId: String = string("commons.orgId")
    val node: String = string("commons.node")
    val url: String = s"$host$path"
    WS.url(url).withQueryString("apiToken" -> apiToken, "apiOrgId" -> orgId, "apiNode" -> node).get()
  }

  def login = Action.async(parse.json) {
    request =>
      var username = (request.body \ "username").as[String]
      var password = (request.body \ "password").as[String]
      val hashedPassword = MissingLibs.passwordHash(password, MissingLibs.HashType.SHA512)
      val hash = Crypto.sign(hashedPassword, username.getBytes("utf-8"))

      println("authenticating with commons")

      commonsRequest(s"/user/authenticate/$hash").flatMap {
        response =>
          response.status match {
            case Http.Status.OK =>
              println("authenticated!")
              commonsRequest(s"/user/profile/$username").flatMap(profile => getOrCreateUser(username, profile.json))
            case _ =>
              Future(Unauthorized("Username password didn't work"))
          }
      }
  }

  def checkLogin = Action {
    implicit request =>
      val maybeToken = request.headers.get(TOKEN)
      maybeToken flatMap {
        token =>
          Cache.getAs[String](token) map {
            email =>
              Ok(Json.obj("user" -> Json.obj("email" -> email))).withToken(token, email)
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

  //  def login = Action(parse.json) {
  //    request =>
  //
  //      val services: CommonsServices = CommonsServices.services
  //      var username = (request.body \ "username").as[String]
  //      var password = (request.body \ "password").as[String]
  //
  //      println(s"connecting with $username/$password")
  //      if (services.connect(username, password)) {
  //        val profile = services.getUserProfile(username)
  //        println("authenticated: " + profile)
  //        Ok("authenticated")
  //      }
  //      else {
  //        Unauthorized("Username password combination failed")
  //      }
  //  }

  //  def old_login = Action(parse.json) {
  //    implicit request =>
  //      val token = java.util.UUID.randomUUID().toString
  //      val email = (request.body \ "email").as[String]
  //      val password = (request.body \ "password").as[String]
  //      val repeatPassword = (request.body \ "repeatPassword").asOpt[String]
  //      if (repeatPassword.isDefined) {
  //        if (password == repeatPassword.get) {
  //          Repo(email).create(password)
  //          Ok(Json.obj("user" -> Json.obj("email" -> email))).withToken(token, email)
  //        }
  //        else {
  //          Unauthorized(Json.obj("problem" -> "Passwords do not match")).discardingToken(TOKEN)
  //        }
  //      }
  //      else {
  //        if (Repo(email).authenticate(password)) {
  //          Ok(Json.obj("user" -> Json.obj("email" -> email))).withToken(token, email)
  //        }
  //        else {
  //          Unauthorized(Json.obj("problem" -> "EMail - Password combination doesn't exist")).discardingToken(TOKEN)
  //        }
  //      }
  //  }


}
