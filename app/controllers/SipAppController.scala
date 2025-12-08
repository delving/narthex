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
import scala.io.Source
import play.api._
import play.api.mvc._

import dataset.DsInfo
import dataset.SipRepo.AvailableSip
import mapping.DefaultMappingRepo
import organization.OrgContext
import triplestore.TripleStore
import web.Utils

@Singleton
class SipAppController @Inject() (
    orgContext: OrgContext
)(implicit
    ec: ExecutionContext,
    ts: TripleStore
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

    // Archive the mapping from the uploaded SIP
    archiveMappingFromSip(spec, datasetContext)

    datasetContext.startSipZipGeneration()
    Ok
  }

  private def archiveMappingFromSip(spec: String, datasetContext: dataset.DatasetContext): Unit = {
    try {
      // Get the latest SIP (which is the one we just uploaded)
      datasetContext.sipRepo.latestSipOpt.foreach { sip =>
        sip.sipMappingOpt.foreach { sipMapping =>
          val prefix = sipMapping.prefix
          val mappingFileName = s"mapping_$prefix.xml"

          // Extract mapping XML from SIP
          sip.entries.get(mappingFileName).foreach { entry =>
            val inputStream = sip.zipFile.getInputStream(entry)
            try {
              val mappingXml = Source.fromInputStream(inputStream, "UTF-8").mkString

              // Compute hash of uploaded mapping
              val uploadedHash = DefaultMappingRepo.computeHash(mappingXml)

              // Save to dataset mapping repo
              val repo = datasetContext.datasetMappingRepo
              repo.saveFromSipUpload(mappingXml, prefix, Some(s"Uploaded via SIP-Creator"))

              // Check if dataset uses default mapping and if hash differs
              DsInfo.withDsInfo(spec, orgContext) { dsInfo =>
                if (dsInfo.usesDefaultMapping) {
                  val defaultPrefix = dsInfo.getDefaultMappingPrefix
                  val defaultVersion = dsInfo.getDefaultMappingVersion

                  // Only check if the prefixes match
                  if (defaultPrefix.contains(prefix)) {
                    // Get the default mapping hash
                    val defaultMappingRepo = new DefaultMappingRepo(orgContext.orgRoot)
                    val targetVersion = defaultVersion.getOrElse("latest")
                    val defaultHash = if (targetVersion == "latest") {
                      defaultMappingRepo.getInfo(prefix).flatMap(_.versions.sortBy(_.timestamp.getMillis).lastOption.map(_.hash))
                    } else {
                      Some(targetVersion)
                    }

                    // If hashes differ, auto-switch to manual mode
                    defaultHash.foreach { dHash =>
                      if (dHash != uploadedHash) {
                        logger.info(s"Dataset $spec: Uploaded mapping hash ($uploadedHash) differs from default mapping hash ($dHash). Auto-switching to manual mode.")
                        dsInfo.setMappingSource("manual", None, None)
                      }
                    }
                  }
                }
              }

              logger.debug(s"Archived mapping from SIP upload for dataset $spec: $mappingFileName (hash: $uploadedHash)")
            } finally {
              inputStream.close()
            }
          }
        }
      }
    } catch {
      case e: Exception =>
        logger.warn(s"Failed to archive mapping from SIP upload for $spec: ${e.getMessage}")
    }
  }

}
