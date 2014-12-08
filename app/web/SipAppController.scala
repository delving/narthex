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

import dataset.Sip
import org.OrgRepo.{AvailableSip, repo}
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc._
import web.Application.{OkFile, SIP_APP_VERSION}

object SipAppController extends Controller with Security {

  def listSipZips() = Secure() { email => implicit request =>
    val availableSips: Seq[AvailableSip] = repo.availableSips
    val uploadedSips: Seq[Sip] = repo.uploadedSips
    val xml =
      <sip-zips sipAppVersion={SIP_APP_VERSION}>
        <available>
          {
            for (availableSip <- availableSips) yield
            <sip-zip>
              <dataset>{ availableSip.datasetName }</dataset>
              <file>{ availableSip.file.getName }</file>
            </sip-zip>
          }
        </available>
        <uploaded>
          {
            for (sip <- uploadedSips) yield
            <sip-zip>
              <dataset>{ sip.datasetName }</dataset>
              <file>{ sip.file.getName }</file>
            </sip-zip>
          }
        </uploaded>
      </sip-zips>
    Ok(xml)
  }

  def downloadSipZip(datasetName: String) = Secure() { email => implicit request =>
    Logger.info(s"Download sip-zip $datasetName")
    val sipFileOpt = repo.datasetRepoOption(datasetName).flatMap { datasetRepo =>
      datasetRepo.sipFiles.headOption
    }
    sipFileOpt.map(OkFile(_)).getOrElse(NotFound(s"No sip-zip for $datasetName"))
  }

  def uploadSipZip(datasetName: String, zipFileName: String) = Secure(parse.temporaryFile) { email => implicit request =>
    repo.datasetRepoOption(datasetName).map { datasetRepo =>
      request.body.moveTo(datasetRepo.sipRepo.createSipZipFile(zipFileName))
      datasetRepo.dropTree()
      datasetRepo.dropSource()
      datasetRepo.startSourceGeneration()
      Ok
    } getOrElse {
      NotAcceptable(Json.obj("err" -> s"Dataset $datasetName not found"))
    }
  }
}
