package controllers

import play.api._
import play.api.mvc._
import play.api.libs.json._

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
          routes.javascript.Dashboard.index,
          routes.javascript.Dashboard.sample,
          routes.javascript.Dashboard.histogram
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

  def checkLogin = Secure() {
    token => email => implicit request => {
      Ok(Json.obj("user" -> Json.obj("email" -> email)))
    }
  }

  /** Logs the user out, i.e. invalidated the token. */
  def logout = Secure(parse.json) {
    token => email => implicit request =>
    // TODO Invalidate token, remove cookie
    Ok.discardingToken(token)
  }

}
