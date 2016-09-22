package web

import play.api.libs.json.Json
import play.api.mvc.{Action, Controller}
import buildinfo.BuildInfo
/**
  * Endpoint for monitoring tools to read some basic info about this instance of the app
  */
class InfoController extends Controller {

  def info = Action {  request =>
    val result = Map(
      "version" -> BuildInfo.version,
      "scalaVersion" -> BuildInfo.version,
      "sbtVersion" -> BuildInfo.sbtVersion,
      "gitCommitSha" -> BuildInfo.gitCommitSha
    )

    Ok(Json.toJson(result))
  }

}
