package controllers

import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.cache.Cache
import play.api.Play.current

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
          routes.javascript.Users.user,
          routes.javascript.Users.createUser,
          routes.javascript.Users.updateUser,
          routes.javascript.Users.deleteUser,
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
      // todo: use password
      Ok(Json.obj("user" -> Json.obj("email" -> email))).withToken(token, email)
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
