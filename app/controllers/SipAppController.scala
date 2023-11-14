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
import scala.concurrent.ExecutionContext
import play.api._
import play.api.mvc._

import dataset.SipRepo.AvailableSip
import organization.OrgContext
import web.Utils

@Singleton
class SipAppController @Inject() (
    orgContext: OrgContext
)(implicit
    ec: ExecutionContext
) extends InjectedController with Logging {

  def listSipZips() = Action.async { request =>
    val availableSips: Seq[AvailableSip] = orgContext.availableSips
    orgContext.uploadedSips.map { uploadedSips =>
      val xml =
        <sip-zips sipAppVersion={Utils.SIP_APP_VERSION}>
          <available>
            {for (availableSip <- availableSips) yield
            <sip-zip>
              <dataset>{availableSip.datasetName}</dataset>
              <file>{availableSip.file.getName}</file>
            </sip-zip>}
          </available>
          <uploaded>
            {for (sip <- uploadedSips) yield
            <sip-zip>
              <dataset>{sip.dsInfoSpec}</dataset>
              <file>{sip.file.getName}</file>
            </sip-zip>}
          </uploaded>
        </sip-zips>
      Ok(xml)
    }
  }

  def downloadSipZip(spec: String) = Action { request =>
    logger.debug(s"Download sip-zip '$spec'")
    val sipFileOpt = orgContext.datasetContext(spec).sipFiles.headOption
    sipFileOpt.map(Utils.okFile(_).withHeaders(s"Content-Disposition" -> s"attachment; filename=$spec.sip.zip")).getOrElse(NotFound(s"No sip-zip for $spec"))
  }

  def uploadSipZip(spec: String, zipFileName: String) = Action(parse.temporaryFile) { request =>
    val datasetContext = orgContext.datasetContext(spec)
    request.body.moveTo(datasetContext.sipRepo.createSipZipFile(zipFileName))
    datasetContext.startSipZipGeneration()
    Ok
  }

}
