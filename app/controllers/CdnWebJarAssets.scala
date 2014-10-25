package controllers

import controllers.routes.{CdnWebJarAssets => WJA}
import play.api.Play
import play.api.Play.current

/**
 * See http://www.jamesward.com/2014/03/20/webjars-now-on-the-jsdelivr-cdn
 */
object CdnWebJarAssets extends WebJarAssets(Assets) {

  def getUrl(file: String) = {
    val maybeContentUrl = Play.configuration.getString("contentUrl")

    maybeContentUrl.map {
      contentUrl =>
        contentUrl + WJA.at(file).url
    } getOrElse WJA.at(file).url
  }

}
