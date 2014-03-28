package controllers

import play.api.mvc._
import play.api.libs.json._
import play.api.cache.Cache
import play.Logger
import play.api.Play.current

/** Manages the security architecture */
trait Security { this: Controller =>
  val TOKEN = "X-XSRF-TOKEN"
  val TOKEN_COOKIE_KEY = "XSRF-TOKEN"
  lazy val CACHE_EXPIRATION = play.api.Play.current.configuration.getInt("cache.expiration").getOrElse(60*2)

  /*
    To make this work seamlessly with Angular, you should read the token from a header called
    X-XSRF-TOKEN, and issue a cookie called XSRF-TOKEN.
    Check out my blog post that explains this in detail:
    http://www.mariussoutier.com/blog/2013/07/14/272/
  */

  def HasToken[A](p: BodyParser[A] = parse.anyContent)(f: String => String => Request[A] => Result): Action[A] =
    Action(p) { implicit request =>
      val maybeToken = request.headers.get(TOKEN)
      Logger.info(s"maybeToken: $maybeToken")
      maybeToken flatMap { token =>
        Cache.getAs[String](token) map { userid =>
          f(token)(userid)(request)
        }
      } getOrElse {
        Logger.info("No Token!")
        Unauthorized(Json.obj("err" -> "No Token"))
      }
    }

  implicit class ResultWithToken(result: Result) {
    def withToken(token: (String, String)): Result = {
      Logger.info(s"with token $token")
      Cache.set(token._1, token._2, CACHE_EXPIRATION)
      result.withCookies(Cookie(TOKEN_COOKIE_KEY, token._1, None, httpOnly = false))
    }

    def discardingToken(token: String): Result = {
      Logger.info(s"discarding token $token")
      Cache.remove(token)
      result.discardingCookies(DiscardingCookie(name = TOKEN_COOKIE_KEY))
    }
  }

}
