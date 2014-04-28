package controllers

import play.api.mvc._
import play.api.libs.json._
import play.api.cache.Cache
import play.Logger
import play.api.Play.current
import scala.concurrent.Future

trait Security { this: Controller =>
  val TOKEN = "X-XSRF-TOKEN"
  val TOKEN_COOKIE_KEY = "XSRF-TOKEN"
  lazy val CACHE_EXPIRATION = play.api.Play.current.configuration.getInt("cache.expiration").getOrElse(60*20)

  /*
    To make this work seamlessly with Angular, you should read the token from a header called
    X-XSRF-TOKEN, and issue a cookie called XSRF-TOKEN.
    Check out my blog post that explains this in detail:
    http://www.mariussoutier.com/blog/2013/07/14/272/
  */

  def Secure[A](p: BodyParser[A] = parse.anyContent)(block: String => String => Request[A] => Result): Action[A] =
    Action(p) { implicit request =>
      val maybeToken = request.headers.get(TOKEN)
      maybeToken flatMap { token =>
        Cache.getAs[String](token) map { email =>
          block(token)(email)(request)
        }
      } getOrElse {
        Logger.info("No Token!")
        Unauthorized(Json.obj("err" -> "No Token"))
      }
    }

  def SecureAsync[A](p: BodyParser[A] = parse.anyContent)(block: String => String => Request[A] => Future[SimpleResult]): Action[A] =
    Action.async(p) { implicit request =>
      request.headers.get(TOKEN) flatMap { token =>
        Cache.getAs[String](token) map { email =>
          block(token)(email)(request)
        }
      } getOrElse {
        Logger.info("No Token!")
        Future.successful(Unauthorized(Json.obj("err" -> "No Token")))
      }
    }

  implicit class ResultWithToken(result: Result) {
    def withToken(token:String, email: String): Result = {
      Logger.info(s"with token $token")
      Cache.set(token, email, CACHE_EXPIRATION)
      result.withCookies(Cookie(TOKEN_COOKIE_KEY, token, None, httpOnly = false))
    }

    def discardingToken(token: String): Result = {
      Logger.info(s"discarding token $token")
      Cache.remove(token)
      result.discardingCookies(DiscardingCookie(name = TOKEN_COOKIE_KEY))
    }
  }

}
