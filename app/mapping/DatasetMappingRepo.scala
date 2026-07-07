//===========================================================================
//    Copyright 2024 Delving B.V.
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

package mapping

import java.io.File
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json._

import scala.io.Source

object DatasetMappingRepo {

  private val logger = Logger(getClass)

  val MAPPINGS_DIR = "mappings"
  val METADATA_FILE = "metadata.json"

  // Case classes for dataset mapping version data
  case class DatasetMappingVersion(
    timestamp: DateTime,
    hash: String,
    filename: String,
    source: String,  // "sip_upload" | "rollback" | "default_copy" | "editor"
    sourceDefault: Option[String],  // e.g., "edm:abc12345" if copied from default
    description: Option[String]
  )

  case class DatasetMappingInfo(
    spec: String,
    prefix: String,  // The mapping prefix (e.g., "edm", "crm")
    versions: List[DatasetMappingVersion],
    currentVersion: Option[String]
  )

  // JSON formats - reuse DateTime formats from DefaultMappingRepo
  implicit val dateTimeReads: Reads[DateTime] = Reads { json =>
    json.validate[String].map(s => DateTime.parse(s))
  }
  implicit val dateTimeWrites: Writes[DateTime] = Writes { dt =>
    JsString(dt.toString)
  }

  implicit val datasetMappingVersionFormat: Format[DatasetMappingVersion] = Json.format[DatasetMappingVersion]
  implicit val datasetMappingInfoFormat: Format[DatasetMappingInfo] = Json.format[DatasetMappingInfo]
}

class DatasetMappingRepo(datasetDir: File) {

  import DatasetMappingRepo._
  import DefaultMappingRepo.{computeHash, generateFilename, TIMESTAMP_FORMAT}

  val mappingsDir = new File(datasetDir, MAPPINGS_DIR)
  val metadataFile = new File(mappingsDir, METADATA_FILE)

  /**
   * Get info about all mapping versions for this dataset
   */
  def getInfo: Option[DatasetMappingInfo] = {
    if (!metadataFile.exists()) {
      None
    } else {
      try {
        val json = Json.parse(FileUtils.readFileToString(metadataFile, "UTF-8"))
        json.asOpt[DatasetMappingInfo]
      } catch {
        case e: Exception =>
          logger.error(s"Error reading dataset mapping metadata: ${e.getMessage}")
          None
      }
    }
  }

  /**
   * List all versions
   */
  def listVersions: List[DatasetMappingVersion] = {
    getInfo.map(_.versions.sortBy(_.timestamp.getMillis).reverse).getOrElse(List.empty)
  }

  /**
   * Get mapping XML content for a specific version.
   *
   * @param version "current" returns ONLY the explicit currentVersion (None if cleared).
   *                "latest" returns the most recent version regardless of currentVersion.
   *                Anything else is treated as a hash.
   *
   * Strict "current" prevents stale-mapping leakage after cross-prefix switches:
   * `clearCurrentVersion()` sets currentVersion=None to mean "no mapping", and
   * the previous fallback to latest would resurrect the old prefix's mapping.
   */
  def getXml(version: String): Option[String] = {
    getInfo.flatMap { info =>
      val targetHash = version match {
        case "current" => info.currentVersion
        case "latest"  => info.currentVersion.orElse(info.versions.sortBy(_.timestamp.getMillis).lastOption.map(_.hash))
        case hash      => Some(hash)
      }

      targetHash.flatMap { hash =>
        info.versions.find(_.hash == hash).flatMap { v =>
          val versionFile = new File(mappingsDir, v.filename)
          if (versionFile.exists()) {
            Some(FileUtils.readFileToString(versionFile, "UTF-8"))
          } else {
            logger.warn(s"Version file not found: ${versionFile.getAbsolutePath}")
            None
          }
        }
      }
    }
  }

  /**
   * Get the current version hash
   */
  def getCurrentVersionHash: Option[String] = {
    getInfo.flatMap(_.currentVersion)
  }

  /**
   * Save a new mapping version from SIP upload
   */
  def saveFromSipUpload(xmlContent: String, prefix: String, description: Option[String] = None): DatasetMappingVersion = {
    saveVersion(xmlContent, prefix, "sip_upload", None, description)
  }

  /**
   * One-shot migration from a SIP's embedded mapping into this repo. Returns
   * `Some(version)` if a new version was saved, `None` if the repo already had
   * versions or the SIP lacks a usable mapping.
   *
   * Legacy datasets created before `DatasetMappingRepo` existed have no entry
   * here; without this migration the harvest/process path would silently keep
   * regenerating SIPs from the prior SIP's mapping forever (stale-mapping
   * risk). Re-used by the mapping-versions UI endpoint.
   */
  def ensureMigratedFromSip(sip: dataset.Sip): Option[DatasetMappingVersion] = {
    if (listVersions.nonEmpty) return None
    sip.sipMappingOpt.flatMap { sipMapping =>
      val prefix = sipMapping.prefix
      val mappingFileName = s"mapping_$prefix.xml"
      sip.entries.get(mappingFileName).flatMap { entry =>
        try {
          val inputStream = sip.zipFile.getInputStream(entry)
          try {
            val mappingXml = Source.fromInputStream(inputStream, "UTF-8").mkString
            Some(saveFromSipUpload(mappingXml, prefix, Some("Auto-migrated from existing SIP")))
          } finally {
            inputStream.close()
          }
        } catch {
          case e: Exception =>
            logger.warn(s"Failed to auto-migrate mapping from SIP: ${e.getMessage}")
            None
        }
      }
    }
  }

  /**
   * Save a new mapping version copied from a default mapping
   */
  def saveFromDefault(xmlContent: String, prefix: String, defaultVersion: String, description: Option[String] = None): DatasetMappingVersion = {
    val sourceDefault = s"$prefix:$defaultVersion"
    saveVersion(xmlContent, prefix, "default_copy", Some(sourceDefault), description.orElse(Some(s"Copied from default mapping $prefix version $defaultVersion")))
  }

  /**
   * Save a new mapping version from the web-based mapping editor
   */
  def saveFromEditor(xmlContent: String, prefix: String, description: Option[String] = None): DatasetMappingVersion = {
    saveVersion(xmlContent, prefix, "editor", None, description.orElse(Some("Saved from mapping editor")))
  }

  /**
   * Rollback to a previous version
   */
  def rollbackTo(hash: String): Option[DatasetMappingVersion] = {
    getInfo.flatMap { info =>
      info.versions.find(_.hash == hash).map { targetVersion =>
        // Get the XML content from the target version
        val versionFile = new File(mappingsDir, targetVersion.filename)
        if (versionFile.exists()) {
          val xmlContent = FileUtils.readFileToString(versionFile, "UTF-8")

          // Create a new version as a rollback
          val newVersion = saveVersion(
            xmlContent,
            info.prefix,
            "rollback",
            None,
            Some(s"Rollback to version ${targetVersion.hash} from ${TIMESTAMP_FORMAT.print(targetVersion.timestamp)}")
          )

          // Set this as the current version
          setCurrentVersion(newVersion.hash)
          newVersion
        } else {
          logger.error(s"Cannot rollback: version file not found: ${versionFile.getAbsolutePath}")
          targetVersion
        }
      }
    }
  }

  /**
   * Clear the current version pointer without deleting any version files.
   * Used when changing the dataset's prefix — old mappings stay archived but
   * none is "current" until the user uploads or picks a new default.
   */
  def clearCurrentVersion(): Boolean = {
    getInfo match {
      case Some(info) =>
        saveMetadata(info.copy(currentVersion = None))
        logger.info(s"Cleared current dataset mapping version")
        true
      case None => false
    }
  }

  /**
   * Set the current/active version
   */
  def setCurrentVersion(hash: String): Boolean = {
    getInfo match {
      case Some(info) if info.versions.exists(_.hash == hash) =>
        val updatedInfo = info.copy(currentVersion = Some(hash))
        saveMetadata(updatedInfo)
        logger.info(s"Set current dataset mapping version to $hash")
        true
      case Some(_) =>
        logger.warn(s"Version $hash not found")
        false
      case None =>
        logger.warn("No mapping info found for dataset")
        false
    }
  }

  /**
   * Check if a version with the given hash exists
   */
  def hasVersion(hash: String): Boolean = {
    getInfo.exists(_.versions.exists(_.hash == hash))
  }

  /**
   * Get the prefix for this dataset's mappings
   */
  def getPrefix: Option[String] = {
    getInfo.map(_.prefix)
  }

  /**
   * Move every version file + the metadata of the old prefix into
   * mappings/archive-<prefix>-<timestamp>/ and leave the repo empty.
   * Archival, not deletion — recoverable by hand if the switch was a mistake.
   */
  private def archivePrefix(oldInfo: DatasetMappingInfo, timestamp: DateTime): Unit = {
    val archiveDir = new File(mappingsDir, s"archive-${oldInfo.prefix}-${timestamp.toString("yyyyMMdd_HHmmss")}")
    archiveDir.mkdirs()
    oldInfo.versions.foreach { v =>
      val f = new File(mappingsDir, v.filename)
      if (f.exists()) FileUtils.moveFile(f, new File(archiveDir, v.filename))
    }
    if (metadataFile.exists()) FileUtils.moveFile(metadataFile, new File(archiveDir, METADATA_FILE))
    logger.warn(s"Dataset ${datasetDir.getName}: prefix switched away from '${oldInfo.prefix}' — " +
      s"archived ${oldInfo.versions.size} mapping version(s) to ${archiveDir.getName}")
  }

  private def saveVersion(
    xmlContent: String,
    prefix: String,
    source: String,
    sourceDefault: Option[String],
    description: Option[String]
  ): DatasetMappingVersion = {
    mappingsDir.mkdirs()

    val timestamp = DateTime.now()
    val hash = computeHash(xmlContent)

    // Update metadata
    val spec = datasetDir.getName
    // INVARIANT: the repo holds mappings for exactly ONE prefix. Saving a
    // mapping for a different prefix means the dataset switched rec-defs —
    // the old prefix's versions are archived (moved aside, never deleted)
    // and the repo starts clean. Without this, a stale cross-prefix
    // "current" mapping leaks into SIP generation nondeterministically.
    getInfo.filter(_.prefix != prefix).foreach { oldInfo =>
      archivePrefix(oldInfo, timestamp)
    }
    val existingInfo = getInfo.getOrElse(
      DatasetMappingInfo(spec = spec, prefix = prefix, versions = List.empty, currentVersion = None)
    )

    // Check if this hash already exists - if so, don't create a new version
    if (existingInfo.versions.exists(_.hash == hash)) {
      logger.info(s"Mapping with hash $hash already exists for dataset $spec, skipping save")
      return existingInfo.versions.find(_.hash == hash).get
    }

    val filename = generateFilename(timestamp, hash)

    // Write the XML file (only if hash is new)
    val versionFile = new File(mappingsDir, filename)
    FileUtils.writeStringToFile(versionFile, xmlContent, "UTF-8")

    val newVersion = DatasetMappingVersion(
      timestamp = timestamp,
      hash = hash,
      filename = filename,
      source = source,
      sourceDefault = sourceDefault,
      description = description
    )

    val updatedInfo = existingInfo.copy(
      prefix = prefix,  // Update prefix in case it changed
      versions = existingInfo.versions :+ newVersion,
      currentVersion = Some(hash)  // New version becomes current
    )

    saveMetadata(updatedInfo)
    logger.info(s"Saved new dataset mapping version: $filename")

    newVersion
  }

  private def saveMetadata(info: DatasetMappingInfo): Unit = {
    mappingsDir.mkdirs()
    val json = Json.prettyPrint(Json.toJson(info))
    FileUtils.writeStringToFile(metadataFile, json, "UTF-8")
  }
}
