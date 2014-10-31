package controllers

import play.api.mvc.Controller

// workaround https://github.com/playframework/playframework/issues/1897
object FreshAssets extends Controller {

    val versionStamp: String = System.currentTimeMillis().toString + "/"

    def at(file: String) = {
      val actualFile = file.replaceAll(versionStamp, "")
      Assets.at("/public", actualFile)
    }

    def getUrl(file: String) = {
      val versionedFile: String = versionStamp + file
      val path = "/public"
      path + controllers.routes.FreshAssets.at(versionedFile).url
    }

}
