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

import play.api.Play.current
import play.api._
import play.api.cache.Cache
import play.api.libs.iteratee.Enumerator
import play.api.libs.json._
import play.api.mvc._
import services.NarthexConfig._

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global

object MainController extends Controller with Security {

  val SIP_APP_VERSION = "P14-04"

  val SIP_APP_URL = s"http://artifactory.delving.org/artifactory/delving/eu/delving/sip-app/$SIP_APP_VERSION/sip-app-$SIP_APP_VERSION-exejar.jar"

  def root = Action { request =>
    Redirect("/narthex/")
  }

  def index = Action { request =>
    Ok(views.html.index(ORG_ID, SIP_APP_URL, SHOW_CATEGORIES, SHOW_THESAURUS))
  }

  def login = Action(parse.json) {
    request =>

      var username = (request.body \ "username").as[String]
      var password = (request.body \ "password").as[String]

      Logger.info(s"Login $username")

      Play.current.configuration.getObjectList("users").map {
        userList =>
          userList.map(_.toConfig).find(u => username == u.getString("user")) match {
            case Some(user) =>
              if (password != user.getString("password")) {
                Unauthorized("Username/password not found")
              }
              else {
                val token = java.util.UUID.randomUUID().toString
                val cachedProfile = CachedProfile(
                  firstName = user.getString("firstName"),
                  lastName = user.getString("lastName"),
                  email = user.getString("email"),
                  apiKey = API_ACCESS_KEYS(0),
                  narthexDomain = NARTHEX_DOMAIN,
                  naveDomain = NAVE_DOMAIN,
                  categoriesEnabled = SHOW_CATEGORIES
                )
                Ok(Json.obj(
                  "firstName" -> cachedProfile.firstName,
                  "lastName" -> cachedProfile.lastName,
                  "email" -> cachedProfile.email,
                  "apiKey" -> cachedProfile.apiKey,
                  "narthexDomain" -> cachedProfile.narthexDomain,
                  "naveDomain" -> cachedProfile.naveDomain,
                  "categoriesEnabled" -> cachedProfile.categoriesEnabled
                )).withToken(token, cachedProfile)
              }

            case None =>
              Unauthorized("Username/password not found")
          }
      } getOrElse Unauthorized("No authentication configuration")
  }

  def checkLogin = Action {
    implicit request =>

      Logger.info(s"Check Login")

      val maybeToken = request.headers.get(TOKEN)
      maybeToken flatMap {
        token =>
          Cache.getAs[CachedProfile](token) map { cachedProfile =>
            Logger.info(s"Check Login Yes: $cachedProfile")
            Ok(Json.obj(
              "firstName" -> cachedProfile.firstName,
              "lastName" -> cachedProfile.lastName,
              "email" -> cachedProfile.email,
              "apiKey" -> cachedProfile.apiKey,
              "narthexDomain" -> cachedProfile.narthexDomain,
              "naveDomain" -> cachedProfile.naveDomain,
              "categoriesEnabled" -> cachedProfile.categoriesEnabled
            )).withToken(token, cachedProfile)
          }
      } getOrElse Unauthorized(Json.obj("err" -> "Check login failed")).discardingToken(TOKEN)
  }

  /** Logs the user out, i.e. invalidated the token. */
  def logout = Action {
    implicit request =>

      Logger.info(s"Logout!")

      request.headers.get(TOKEN) match {
        case Some(token) =>
          Ok.discardingToken(token)
        case None =>
          Unauthorized(Json.obj("err" -> "Logout failed"))
      }
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
          routes.javascript.AppController.listDatasets,
          routes.javascript.AppController.listPrefixes,
          routes.javascript.AppController.create,
          routes.javascript.AppController.harvest,
          routes.javascript.AppController.setHarvestCron,
          routes.javascript.AppController.setMetadata,
          routes.javascript.AppController.setPublication,
          routes.javascript.AppController.setCategories,
          routes.javascript.AppController.datasetInfo,
          routes.javascript.AppController.command,
          routes.javascript.AppController.datasetProgress,
          routes.javascript.AppController.nodeStatus,
          routes.javascript.AppController.index,
          routes.javascript.AppController.sample,
          routes.javascript.AppController.histogram,
          routes.javascript.AppController.setRecordDelimiter,
          routes.javascript.AppController.listConceptSchemes,
          routes.javascript.AppController.searchConceptScheme,
          routes.javascript.AppController.getTermSourcePaths,
          routes.javascript.AppController.getTermMappings,
          routes.javascript.AppController.setTermMapping,
          routes.javascript.AppController.getThesaurusMappings,
          routes.javascript.AppController.setThesaurusMapping,
          routes.javascript.AppController.getCategoryList,
          routes.javascript.AppController.listSheets,
          routes.javascript.AppController.getCategorySourcePaths,
          routes.javascript.AppController.getCategoryMappings,
          routes.javascript.AppController.setCategoryMapping,
          routes.javascript.AppController.gatherCategoryCounts,
          routes.javascript.AppController.listSipFiles,
          routes.javascript.AppController.deleteLatestSipFile
        )
      ).as(JAVASCRIPT)
  }
}
