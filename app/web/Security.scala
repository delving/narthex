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

import play.Logger
import play.api.Play.current
import play.api.cache.Cache
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.Future

trait Security { this: Controller =>
  val TOKEN = "X-XSRF-TOKEN"
  val TOKEN_COOKIE_KEY = "XSRF-TOKEN"
  lazy val CACHE_EXPIRATION = play.api.Play.current.configuration.getInt("cache.expiration").getOrElse(60*60*4)

  /*
    To make this work seamlessly with Angular, you should read the token from a header called
    X-XSRF-TOKEN, and issue a cookie called XSRF-TOKEN.
    Check out my blog post that explains this in detail:
    http://www.mariussoutier.com/blog/2013/07/14/272/
  */

  def Secure[A](p: BodyParser[A] = parse.anyContent)(block: String => Request[A] => SimpleResult): Action[A] =
    Action(p) { implicit request =>
      val maybeToken = request.headers.get(TOKEN)
      maybeToken flatMap { token =>
        Cache.getAs[String](token) map { email =>
          block(token)(request).withToken(token, email)
        }
      } getOrElse {
        Logger.info("No Token Secure!")
        Unauthorized(Json.obj("err" -> "No Token in secure action"))
      }
    }

  def SecureAsync[A](p: BodyParser[A] = parse.anyContent)(block: String => String => Request[A] => Future[SimpleResult]): Action[A] =
    Action.async(p) { implicit request =>
      request.headers.get(TOKEN) flatMap { token =>
        Cache.getAs[String](token) map { email =>
          block(token)(email)(request)
        }
      } getOrElse {
        Logger.info("No Token SecureAsync!")
        Future.successful(Unauthorized(Json.obj("err" -> "No Token in secure async action")))
      }
    }

  implicit class ResultWithToken(result: SimpleResult) {
    def withToken(token:String, email: String): SimpleResult = {
      Cache.set(token, email, CACHE_EXPIRATION)
      result.withCookies(Cookie(TOKEN_COOKIE_KEY, token, None, httpOnly = false))
    }

    def discardingToken(token: String): SimpleResult = {
      Logger.info(s"discarding token $token")
      Cache.remove(token)
      result.discardingCookies(DiscardingCookie(name = TOKEN_COOKIE_KEY))
    }
  }

}
