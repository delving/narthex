package services

import play.api.Application
import play.api.Play
import play.api.http.{MimeTypes, HeaderNames}
import play.api.libs.ws.WS
import play.api.mvc.{Result, Results, Action, Controller}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

object OAuth2 extends Controller {

  lazy val oauth2 = new OAuth2(Play.current)

  def config(key: String) = Play.current.configuration.getString(key).get
  lazy val getTokenUrl = config("oauth2.tokenUrl")
  lazy val authId = config("oauth2.id")
  lazy val authSecret = config("oauth2.secret")
  lazy val baseUrl = config("oauth2.baseUrl")
  lazy val successUrl = config("oauth2.successUrl")

  def callback(codeOpt: Option[String] = None, stateOpt: Option[String] = None) = Action.async { implicit request =>
    val resultFutureOpt: Option[Future[Result]] = for {
      code <- codeOpt
      state <- stateOpt
      oauthState <- request.session.get("oauth-state")
    } yield {
        if (state == oauthState) {
          oauth2.getToken(code).map { accessToken =>
            Redirect(services.routes.OAuth2.success()).withSession("oauth-token" -> accessToken)
          }.recover {
            case ex: IllegalStateException => Unauthorized(ex.getMessage)
          }
        }
        else {
          Future.successful(BadRequest("Invalid oauth login"))
        }
      }
    resultFutureOpt.getOrElse(Future.successful(BadRequest("No parameters supplied")))
  }

  def success() = Action.async { request =>
    implicit val app = Play.current
    request.session.get("oauth-token").fold(Future.successful(Unauthorized("No way Jose"))) { authToken =>
      WS.url(successUrl)
        .withHeaders(HeaderNames.AUTHORIZATION -> s"token $authToken")
        .get()
        .map(response => Ok(response.json))
    }
  }
}

class OAuth2(application: Application) {

  import services.OAuth2._

  /*
    def index = Action { implicit request =>
    val oauth2 = new OAuth2(Play.current)
    val callbackUrl = util.routes.OAuth2.callback(None, None).absoluteURL()
    val scope = "repo"   // github scope - request repo access
    val state = UUID.randomUUID().toString  // random confirmation string
    val redirectUrl = oauth2.getAuthorizationUrl(callbackUrl, scope, state)
    Ok(views.html.index("Your new application is ready.", redirectUrl)).
      withSession("oauth-state" -> state)
  }

 <a href="@redirectUrl"><em><b>Log in with GITHUB!</b></em></a>
   */

  def getAuthorizationUrl(redirectUri: String, state: String): String = {
    baseUrl.format(authId, redirectUri, state)
  }

  def getToken(code: String): Future[String] = {
    val tokenResponse = WS.url(getTokenUrl)(application)
      .withQueryString(
        "client_id" -> authId,
        "client_secret" -> authSecret,
        "code" -> code
      )
      .withHeaders(HeaderNames.ACCEPT -> MimeTypes.JSON)
      .post(Results.EmptyContent())

    tokenResponse.flatMap { response =>
      val accessTokenOpt = (response.json \ "access_token").asOpt[String]
      accessTokenOpt.fold(Future.failed[String](new IllegalStateException("No access!"))) { accessToken =>
        Future.successful(accessToken)
      }
    }
  }
}

