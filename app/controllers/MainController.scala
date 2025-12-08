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

import javax.inject._
import java.io.File
import java.time.Duration
import scala.concurrent.duration._
import play.api._
import play.api.cache.{SyncCacheApi, Cached}
import play.api.mvc._
import play.api.routing.JavaScriptReverseRoute
import org.webjars.play.WebJarsUtil

import init.NarthexConfig
import web.Utils
import buildinfo.BuildInfo

@Singleton
class MainController @Inject() (
    cached: Cached,
    narthexConfig: NarthexConfig
)(implicit
    assets: AssetsFinder,
    webJarsUtil: WebJarsUtil
) extends InjectedController with Logging {

  /**
    * Caching action that caches an OK response for the given amount of time with the key.
    * NotFound will be cached for 5 mins. Any other status will not be cached.
    */
  def Caching(key: String, okDuration: Duration) = {
    cached
      .status(_ => key, OK, okDuration.getSeconds.toInt)
      .includeStatus(NOT_FOUND, 5.minutes.toSeconds.toInt)
  }

  def root: Action[AnyContent] = Action { implicit request =>
    Redirect("/narthex/")
  }

  def index: Action[AnyContent] = Action { implicit request =>
    Ok(views.html.index(narthexConfig.orgId, narthexConfig.sipAppDownloadUrl, BuildInfo.version,
      narthexConfig.supportedDatasetTypes.mkString(","), BuildInfo.commitSha,
      narthexConfig.naveDomain, narthexConfig.enableIncrementalHarvest, narthexConfig.enableDefaultMappings))
      .withHeaders(CACHE_CONTROL -> "no-cache")
  }

  /**
    * In actual deployment scenarios this will result in a view on the page that provides links to Nave,
    * nave-search and narthex itself.
    */
  def logout: Action[AnyContent] = Action {
    Unauthorized(views.html.logout())
  }

  def OkFile(file: File, attempt: Int = 0): Result = Utils.okFile(file, attempt)

  def OkXml(xml: String): Result = Utils.okXml(xml)

  /**
    * Retrieves all routes via reflection.
    * http://stackoverflow.com/questions/12012703/less-verbose-way-of-generating-play-2s-javascript-router
    */
  private val routeCache = {
    val jsRoutesClass: Class[routes.javascript] = classOf[routes.javascript]
    val controllers = jsRoutesClass.getFields.map(_.get(null))
    for (
      controller <- controllers;
      method <- controller.getClass.getDeclaredMethods if method.getReturnType == classOf[play.api.routing.JavaScriptReverseRoute]
    ) yield method.invoke(controller).asInstanceOf[play.api.routing.JavaScriptReverseRoute]
  }.toIndexedSeq

  /**
    * Returns the JavaScript router that the client can use for "type-safe" routes.
    */
  def jsRoutes: Action[AnyContent] = Action { implicit request =>
    Ok(play.api.routing.JavaScriptReverseRouter("jsRoutes")(routeCache:_*)).as("text/javascript")
  }

}
