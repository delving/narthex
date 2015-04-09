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

import java.io.{File, FileInputStream, FileNotFoundException}
import java.util.UUID

import org.ActorStore.{NXActor, NXProfile}
import org.OrgContext._
import play.api.Play.current
import play.api._
import play.api.cache.Cache
import play.api.http.{HeaderNames, MimeTypes}
import play.api.libs.iteratee.Enumerator
import play.api.libs.json._
import play.api.libs.ws.WS
import play.api.mvc._
import services.MailService.{MailDatasetError, MailProcessingComplete}

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object MainController extends Controller with Security {

  val SIP_APP_VERSION = "1.0.7"

  val SIP_APP_URL = s"http://artifactory.delving.org/artifactory/delving/eu/delving/sip-app/$SIP_APP_VERSION/sip-app-$SIP_APP_VERSION-exejar.jar"

  def root = Action { request =>
    Redirect("/narthex/")
  }

  def index = Action { request =>
    val state = UUID.randomUUID().toString
    createOAuthUrl(state).map { oauthUrl =>
      Ok(views.html.index(ORG_ID, SIP_APP_URL, oauthUrl)).withSession("oauth-state" -> state)
    } getOrElse {
      Ok(views.html.index(ORG_ID, SIP_APP_URL, ""))
    }
  }

  def oauthCallback(codeOpt: Option[String] = None, stateOpt: Option[String] = None) = Action.async { implicit request =>
    def getToken(code: String): Future[String] = {
      WS.url(OAUTH_TOKEN_URL)
        .withQueryString(
          "client_id" -> OAUTH_ID.get,
          "client_secret" -> OAUTH_SECRET.get,
          "code" -> code
        )
        .withHeaders(HeaderNames.ACCEPT -> MimeTypes.JSON).post(Results.EmptyContent()).flatMap { response =>
        val tokenOpt = (response.json \ "access_token").asOpt[String]
        tokenOpt.map(token => Future.successful(token)).getOrElse(Future.failed(new IllegalStateException("No access!")))
      }
    }
    val resultFutureOpt: Option[Future[Result]] = for {
      code <- codeOpt
      state <- stateOpt
      oauthState <- request.session.get("oauth-state")
    } yield {
        if (state == oauthState) {
          getToken(code).map { token =>
            Logger.info(s"We got us a token $token")
            Redirect("/narthex/_oauth-success").withSession("oauth-token" -> token)
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

  def oauthSuccess() = Action.async { request =>
    val fetchDataUrl = "https://api.github.com/user"
    request.session.get("oauth-token").map { token =>
      WS.url(fetchDataUrl).withHeaders(HeaderNames.AUTHORIZATION -> s"token $token").get().map { response =>
        val login = (response.json \ "login").as[String]
        Logger.info(s"OAuth success $login")
        Ok(login)
      }
    } getOrElse {
      Future.successful(Unauthorized("No oauth token"))
    }
  }

  def login = Action(parse.json) { implicit request =>

    var username = (request.body \ "username").as[String]
    var password = (request.body \ "password").as[String]
    Logger.info(s"Login $username")

    Play.current.configuration.getObjectList("users").map { userList =>
      userList.map(_.toConfig).find(u => username == u.getString("user")) match {
        case Some(user) =>
          if (password != user.getString("password")) {
            Unauthorized("Username/password not found")
          }
          else {
            val session = ActorSession(
              NXActor(username, None, Some(NXProfile(
                user.getString("firstName"),
                user.getString("lastName"),
                user.getString("email")
              ))),
              apiKey = API_ACCESS_KEYS(0),
              narthexDomain = NARTHEX_DOMAIN,
              naveDomain = NAVE_DOMAIN,
              singleTripleStore = SINGLE_TRIPLE_STORE
            )
            Ok(Json.toJson(session)).withSession(session)
          }

        case None =>
          Unauthorized("Username/password not found")
      }
    } getOrElse {
      val resultFuture = orgContext.us.authenticate(username, password).map { nxActorOpt: Option[NXActor] =>
        nxActorOpt.map { nxActor =>
          val session = ActorSession(
            nxActor,
            apiKey = API_ACCESS_KEYS(0),
            narthexDomain = NARTHEX_DOMAIN,
            naveDomain = NAVE_DOMAIN,
            singleTripleStore = SINGLE_TRIPLE_STORE
          )
          Ok(Json.toJson(session)).withSession(session)
        } getOrElse Unauthorized("Username/password not found")
      }
      Await.result(resultFuture, 10.seconds)
    }
  }

  def checkLogin = Action { implicit request =>
    val maybeToken = request.headers.get(TOKEN)
    maybeToken flatMap {
      token =>
        Cache.getAs[ActorSession](token) map { session =>
          Ok(Json.toJson(session)).withSession(session)
        }
    } getOrElse Unauthorized(Json.obj("err" -> "Check login failed")).discardingToken(TOKEN)
  }

  /** Logs the user out, i.e. invalidated the token. */
  def logout = Action { implicit request =>
    Logger.info(s"Logout")
    request.headers.get(TOKEN) match {
      case Some(token) =>
        Ok.discardingToken(token)
      case None =>
        Unauthorized(Json.obj("err" -> "Logout failed"))
    }
  }

  def setProfile() = SecureAsync(parse.json) { session => implicit request =>
    val nxProfile = NXProfile(
      firstName = (request.body \ "firstName").as[String],
      lastName = (request.body \ "lastName").as[String],
      email = (request.body \ "email").as[String]
    )
    val setOp = orgContext.us.setProfile(session.actor, nxProfile).map { ok =>
      val newSession = session.copy(actor = session.actor.copy(profileOpt = Some(nxProfile)))
      Ok(Json.toJson(newSession)).withSession(newSession)
    }
    setOp.onFailure {
      case e: Throwable =>
        Logger.error("Problem setting profile", e)
    }
    setOp
  }

  def listActors = Secure() { session => implicit request =>
    Ok(Json.obj("actorList" -> orgContext.us.listSubActors(session.actor)))
  }

  def createActor() = SecureAsync(parse.json) { session => implicit request =>
    val username = (request.body \ "username").as[String]
    val password = (request.body \ "password").as[String]
    orgContext.us.createSubActor(session.actor, username, password).map { actorOpt =>
      Ok(Json.obj("actorList" -> orgContext.us.listSubActors(session.actor)))
    }
  }

  def setPassword() = SecureAsync(parse.json) { session => implicit request =>
    val newPassword = (request.body \ "newPassword").as[String]
    orgContext.us.setPassword(session.actor, newPassword).map(alright => Ok)
  }

  // todo: move this
  def OkFile(file: File, attempt: Int = 0): Result = {
    try {
      val input = new FileInputStream(file)
      val resourceData = Enumerator.fromStream(input)
      val contentType = if (file.getName.endsWith(".json")) "application/json" else "text/plain; charset=utf-8"
      Result(
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

  // todo: move this
  def OkXml(xml: String): Result = {
    Result(
      ResponseHeader(OK, Map(
        CONTENT_LENGTH -> xml.length().toString,
        CONTENT_TYPE -> "application/xml"
      )),
      body = Enumerator(xml.getBytes)
    )
  }

  /**
   * Returns the JavaScript router that the client can use for "type-safe" routes.
   * @param varName The name of the global variable, defaults to `jsRoutes`
   */
  def jsRoutes(varName: String = "jsRoutes") = Action {
    implicit request =>
      Ok(// IntelliJ Idea shows errors here but it compiles:
        Routes.javascriptRouter(varName)(
          routes.javascript.MainController.login,
          routes.javascript.MainController.checkLogin,
          routes.javascript.MainController.logout,
          routes.javascript.MainController.setProfile,
          routes.javascript.MainController.listActors,
          routes.javascript.MainController.createActor,
          routes.javascript.MainController.setPassword,
          routes.javascript.AppController.listDatasets,
          routes.javascript.AppController.listPrefixes,
          routes.javascript.AppController.createDataset,
          routes.javascript.AppController.setDatasetProperties,
          routes.javascript.AppController.setSkosifiedField,
          routes.javascript.AppController.datasetInfo,
          routes.javascript.AppController.command,
          routes.javascript.AppController.toggleDatasetProduction,
          routes.javascript.AppController.datasetProgress,
          routes.javascript.AppController.nodeStatus,
          routes.javascript.AppController.index,
          routes.javascript.AppController.sample,
          routes.javascript.AppController.histogram,
          routes.javascript.AppController.setRecordDelimiter,
          routes.javascript.AppController.getTermVocabulary,
          routes.javascript.AppController.getTermMappings,
          routes.javascript.AppController.getCategoryMappings,
          routes.javascript.AppController.toggleTermMapping,
          routes.javascript.AppController.getCategoryList,
          routes.javascript.AppController.listSheets,
          routes.javascript.AppController.gatherCategoryCounts,
          routes.javascript.AppController.listSipFiles,
          routes.javascript.AppController.deleteLatestSipFile,
          routes.javascript.AppController.listVocabularies,
          routes.javascript.AppController.createVocabulary,
          routes.javascript.AppController.deleteVocabulary,
          routes.javascript.AppController.setVocabularyProperties,
          routes.javascript.AppController.vocabularyInfo,
          routes.javascript.AppController.vocabularyStatistics,
          routes.javascript.AppController.getSkosMappings,
          routes.javascript.AppController.toggleSkosMapping,
          routes.javascript.AppController.searchVocabulary,
          routes.javascript.AppController.getVocabularyLanguages
        )
      ).as(JAVASCRIPT)
  }

  def testEmail(messageType: String) = Action { request =>
    messageType match {
      case "processing-complete" =>
        Ok(views.html.email.processingComplete(MailProcessingComplete(
          spec = "spec",
          ownerEmailOpt = Some("servers@delving.eu"),
          validString = "1000000000",
          invalidString = "0"
        )))

      case "dataset-error" =>
        var throwable: Throwable = null
        try {
          throw new Exception("This is the exception's message")
        }
        catch {
          case e: Throwable =>
            throwable = e
        }
        Ok(views.html.email.datasetError(MailDatasetError(
          spec = "spec",
          ownerEmailOpt = Some("servers@delving.eu"),
          throwable.getMessage,
          Some(throwable)
        )))

      case badEmailType =>
        Ok(views.html.email.emailNotFound(badEmailType, Seq(
          "processing-complete",
          "dataset-error"
        )))
    }
  }

}
