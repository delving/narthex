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

import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.cache.Cache
import play.api.Play.current
import services.FileRepository

/** Application controller, handles authentication */
object Application extends Controller with Security {

  /** Serves the index page, see views/index.scala.html */
  def index = Action {
    Ok(views.html.index())
  }

  /**
   * Returns the JavaScript router that the client can use for "type-safe" routes.
   * @param varName The name of the global variable, defaults to `jsRoutes`
   */
  def jsRoutes(varName: String = "jsRoutes") = Action {
    implicit request =>
      Ok(
        Routes.javascriptRouter(varName)(
          routes.javascript.Application.login,
          routes.javascript.Application.checkLogin,
          routes.javascript.Application.logout,
          routes.javascript.Dashboard.list,
          routes.javascript.Dashboard.work,
          routes.javascript.Dashboard.status,
          routes.javascript.Dashboard.nodeStatus,
          routes.javascript.Dashboard.index,
          routes.javascript.Dashboard.sample,
          routes.javascript.Dashboard.histogram,
          routes.javascript.Dashboard.uniqueText,
          routes.javascript.Dashboard.histogramText
        )
      ).as(JAVASCRIPT)
  }

  /**
   * Log-in a user. Pass the credentials as JSON body.
   * @return The token needed for subsequent requests
   */
  def login = Action(parse.json) {
    implicit request =>
      val token = java.util.UUID.randomUUID().toString
      val email = (request.body \ "email").as[String]
      val password = (request.body \ "password").as[String]
      val repeatPassword = (request.body \ "repeatPassword").asOpt[String]
      if (repeatPassword.isDefined) {
        if (password == repeatPassword.get) {
          FileRepository(email).create(password)
          Ok(Json.obj("user" -> Json.obj("email" -> email))).withToken(token, email)
        }
        else {
          Unauthorized(Json.obj("problem" -> "Passwords do not match"))
        }
      }
      else {
        if (FileRepository(email).authenticate(password)) {
          Ok(Json.obj("user" -> Json.obj("email" -> email))).withToken(token, email)
        }
        else {
          Unauthorized(Json.obj("problem" -> "EMail - Password combination doesn't exist"))
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
        Unauthorized(Json.obj("err" -> "No Token"))
      }
  }

  /** Logs the user out, i.e. invalidated the token. */
  def logout = Action {
    implicit request =>
      request.headers.get(TOKEN) match {
        case Some(token) =>
          Ok.discardingToken(token)
        case None =>
          Unauthorized(Json.obj("err" -> "No Token"))
      }
  }

}
