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
import java.security.MessageDigest
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import play.api.Logger
import play.api.libs.json._

object DefaultMappingRepo {

  private val logger = Logger(getClass)

  val VERSIONS_DIR = "versions"
  val METADATA_FILE = "metadata.json"
  val TIMESTAMP_FORMAT = DateTimeFormat.forPattern("yyyyMMdd_HHmmss")

  // Case classes for mapping version data
  case class MappingVersion(
    timestamp: DateTime,
    hash: String,
    filename: String,
    source: String,  // "upload" | "copy_from_dataset"
    sourceDataset: Option[String],
    notes: Option[String]
  )

  // Full info for a named mapping (prefix/name)
  case class NamedMapping(
    prefix: String,
    name: String,           // slug: "museum-mapping"
    displayName: String,    // "Museum Mapping"
    versions: List[MappingVersion],
    currentVersion: Option[String]
  )

  // Lightweight info for listing named mappings
  case class NamedMappingLight(
    prefix: String,
    name: String,
    displayName: String,
    latestVersion: Option[String],
    latestTimestamp: Option[DateTime],
    versionCount: Int,
    currentVersion: Option[String]
  )

  // Grouping of all mappings under a prefix
  case class PrefixMappings(
    prefix: String,
    mappings: List[NamedMappingLight]
  )

  // JSON formats
  implicit val dateTimeReads: Reads[DateTime] = Reads { json =>
    json.validate[String].map(s => DateTime.parse(s))
  }
  implicit val dateTimeWrites: Writes[DateTime] = Writes { dt =>
    JsString(dt.toString)
  }

  implicit val mappingVersionFormat: Format[MappingVersion] = Json.format[MappingVersion]
  implicit val namedMappingFormat: Format[NamedMapping] = Json.format[NamedMapping]
  implicit val namedMappingLightFormat: Format[NamedMappingLight] = Json.format[NamedMappingLight]
  implicit val prefixMappingsFormat: Format[PrefixMappings] = Json.format[PrefixMappings]

  def computeHash(content: String): String = {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(content.getBytes("UTF-8"))
    digest.map("%02x".format(_)).mkString.take(8)
  }

  def generateFilename(timestamp: DateTime, hash: String): String = {
    s"${TIMESTAMP_FORMAT.print(timestamp)}_$hash.xml"
  }

  /**
   * Generate a slug from a display name
   * "Museum Mapping" -> "museum-mapping"
   */
  def generateSlug(displayName: String): String = {
    displayName.toLowerCase
      .replaceAll("[^a-z0-9\\s-]", "")  // Remove special chars except spaces and hyphens
      .replaceAll("\\s+", "-")           // Replace spaces with hyphens
      .replaceAll("-+", "-")             // Collapse multiple hyphens
      .replaceAll("^-|-$", "")           // Remove leading/trailing hyphens
  }
}

class DefaultMappingRepo(orgRoot: File) {

  import DefaultMappingRepo._

  val defaultMappingsDir = new File(orgRoot, "default-mappings")

  // Run migration on initialization
  migrateOldStructure()

  /**
   * Migrate old structure (prefix/versions/) to new structure (prefix/default/versions/)
   */
  private def migrateOldStructure(): Unit = {
    if (!defaultMappingsDir.exists()) return

    defaultMappingsDir.listFiles().filter(_.isDirectory).foreach { prefixDir =>
      val oldMetadataFile = new File(prefixDir, METADATA_FILE)
      val oldVersionsDir = new File(prefixDir, VERSIONS_DIR)

      // Check if this is old structure (metadata.json directly in prefix dir, not in subdirs)
      if (oldMetadataFile.exists() && oldVersionsDir.exists()) {
        // Check if there are already named mapping subdirs (new structure)
        val hasNamedMappings = prefixDir.listFiles().exists { f =>
          f.isDirectory && f.getName != VERSIONS_DIR && new File(f, METADATA_FILE).exists()
        }

        if (!hasNamedMappings) {
          // This is old structure - migrate to "default" subfolder
          logger.info(s"Migrating old mapping structure for prefix ${prefixDir.getName}")

          val defaultDir = new File(prefixDir, "default")
          val newVersionsDir = new File(defaultDir, VERSIONS_DIR)
          newVersionsDir.mkdirs()

          // Move versions
          if (oldVersionsDir.exists() && oldVersionsDir.isDirectory) {
            oldVersionsDir.listFiles().foreach { versionFile =>
              val newFile = new File(newVersionsDir, versionFile.getName)
              FileUtils.moveFile(versionFile, newFile)
            }
            oldVersionsDir.delete()
          }

          // Read old metadata and convert to new format
          try {
            val oldJson = Json.parse(FileUtils.readFileToString(oldMetadataFile, "UTF-8"))
            val prefix = (oldJson \ "prefix").asOpt[String].getOrElse(prefixDir.getName)
            val oldVersions = (oldJson \ "versions").asOpt[List[JsValue]].getOrElse(List.empty)
            val currentVersion = (oldJson \ "currentVersion").asOpt[String]

            // Convert old versions to new format (description -> notes)
            val newVersions = oldVersions.map { v =>
              MappingVersion(
                timestamp = (v \ "timestamp").as[DateTime],
                hash = (v \ "hash").as[String],
                filename = (v \ "filename").as[String],
                source = (v \ "source").as[String],
                sourceDataset = (v \ "sourceDataset").asOpt[String],
                notes = (v \ "description").asOpt[String].orElse((v \ "notes").asOpt[String])
              )
            }

            val newMapping = NamedMapping(
              prefix = prefix,
              name = "default",
              displayName = "Default",
              versions = newVersions,
              currentVersion = currentVersion
            )

            // Write new metadata in default subfolder
            val newMetadataFile = new File(defaultDir, METADATA_FILE)
            val json = Json.prettyPrint(Json.toJson(newMapping))
            FileUtils.writeStringToFile(newMetadataFile, json, "UTF-8")

            // Delete old metadata file
            oldMetadataFile.delete()

            logger.info(s"Successfully migrated ${prefixDir.getName} to new structure with ${newVersions.length} versions")
          } catch {
            case e: Exception =>
              logger.error(s"Error migrating prefix ${prefixDir.getName}: ${e.getMessage}")
          }
        }
      }
    }
  }

  /**
   * List all prefixes that have default mappings
   */
  def listPrefixes(): Seq[String] = {
    if (!defaultMappingsDir.exists()) {
      Seq.empty
    } else {
      defaultMappingsDir.listFiles()
        .filter(_.isDirectory)
        .filter(hasAnyMappings)
        .map(_.getName)
        .toSeq
        .sorted
    }
  }

  /**
   * Check if a prefix dir has any named mappings
   */
  private def hasAnyMappings(prefixDir: File): Boolean = {
    prefixDir.listFiles().exists { f =>
      f.isDirectory && new File(f, METADATA_FILE).exists()
    }
  }

  /**
   * List all named mappings for a prefix
   */
  def listMappingsForPrefix(prefix: String): Seq[NamedMappingLight] = {
    val prefixDir = new File(defaultMappingsDir, prefix)
    if (!prefixDir.exists()) {
      Seq.empty
    } else {
      prefixDir.listFiles()
        .filter(_.isDirectory)
        .filter(d => new File(d, METADATA_FILE).exists())
        .flatMap(d => getLight(prefix, d.getName))
        .toSeq
        .sortBy(_.displayName)
    }
  }

  /**
   * List all prefixes with their mappings
   */
  def listAll(): Seq[PrefixMappings] = {
    listPrefixes().map { prefix =>
      PrefixMappings(prefix, listMappingsForPrefix(prefix).toList)
    }
  }

  /**
   * Get full info for a named mapping including all versions
   */
  def getInfo(prefix: String, name: String): Option[NamedMapping] = {
    val mappingDir = new File(new File(defaultMappingsDir, prefix), name)
    val metadataFile = new File(mappingDir, METADATA_FILE)

    if (!metadataFile.exists()) {
      None
    } else {
      try {
        val json = Json.parse(FileUtils.readFileToString(metadataFile, "UTF-8"))
        json.asOpt[NamedMapping]
      } catch {
        case e: Exception =>
          logger.error(s"Error reading metadata for $prefix/$name: ${e.getMessage}")
          None
      }
    }
  }

  /**
   * Get lightweight info for a named mapping
   */
  def getLight(prefix: String, name: String): Option[NamedMappingLight] = {
    getInfo(prefix, name).map { info =>
      val latestVersion = info.versions.sortBy(_.timestamp.getMillis).lastOption
      NamedMappingLight(
        prefix = info.prefix,
        name = info.name,
        displayName = info.displayName,
        latestVersion = latestVersion.map(_.hash),
        latestTimestamp = latestVersion.map(_.timestamp),
        versionCount = info.versions.length,
        currentVersion = info.currentVersion
      )
    }
  }

  /**
   * Get mapping XML content for a specific version
   * @param version Either a hash or "latest"
   */
  def getXml(prefix: String, name: String, version: String): Option[String] = {
    getInfo(prefix, name).flatMap { info =>
      val targetVersion = if (version == "latest") {
        info.currentVersion.orElse(info.versions.sortBy(_.timestamp.getMillis).lastOption.map(_.hash))
      } else {
        Some(version)
      }

      targetVersion.flatMap { hash =>
        info.versions.find(_.hash == hash).flatMap { v =>
          val versionFile = new File(new File(new File(new File(defaultMappingsDir, prefix), name), VERSIONS_DIR), v.filename)
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
   * Create a new named mapping under a prefix
   */
  def createMapping(prefix: String, displayName: String): NamedMapping = {
    val name = generateSlug(displayName)
    val finalName = ensureUniqueName(prefix, name)

    val mapping = NamedMapping(
      prefix = prefix,
      name = finalName,
      displayName = displayName,
      versions = List.empty,
      currentVersion = None
    )

    saveMetadata(prefix, finalName, mapping)
    logger.info(s"Created new named mapping: $prefix/$finalName")

    mapping
  }

  /**
   * Ensure the name is unique within the prefix by appending a number if needed
   */
  private def ensureUniqueName(prefix: String, baseName: String): String = {
    val prefixDir = new File(defaultMappingsDir, prefix)
    if (!prefixDir.exists()) {
      return baseName
    }

    var name = baseName
    var counter = 2
    while (new File(prefixDir, name).exists()) {
      name = s"$baseName-$counter"
      counter += 1
    }
    name
  }

  /**
   * Save a new mapping version
   */
  def saveVersion(
    prefix: String,
    name: String,
    xmlContent: String,
    source: String,
    sourceDataset: Option[String],
    notes: Option[String]
  ): MappingVersion = {
    val mappingDir = new File(new File(defaultMappingsDir, prefix), name)
    val versionsDir = new File(mappingDir, VERSIONS_DIR)
    versionsDir.mkdirs()

    val timestamp = DateTime.now()
    val hash = computeHash(xmlContent)
    val filename = generateFilename(timestamp, hash)

    // Write the XML file
    val versionFile = new File(versionsDir, filename)
    FileUtils.writeStringToFile(versionFile, xmlContent, "UTF-8")

    val newVersion = MappingVersion(
      timestamp = timestamp,
      hash = hash,
      filename = filename,
      source = source,
      sourceDataset = sourceDataset,
      notes = notes
    )

    // Update metadata
    val existingInfo = getInfo(prefix, name).getOrElse(
      NamedMapping(prefix = prefix, name = name, displayName = name, versions = List.empty, currentVersion = None)
    )

    // Check if this hash already exists
    if (existingInfo.versions.exists(_.hash == hash)) {
      logger.info(s"Mapping with hash $hash already exists for $prefix/$name")
      return existingInfo.versions.find(_.hash == hash).get
    }

    val updatedInfo = existingInfo.copy(
      versions = existingInfo.versions :+ newVersion,
      currentVersion = existingInfo.currentVersion.orElse(Some(hash))  // Set as current if first version
    )

    saveMetadata(prefix, name, updatedInfo)
    logger.info(s"Saved new mapping version for $prefix/$name: $filename")

    newVersion
  }

  /**
   * Set the current/active version for a named mapping
   */
  def setCurrentVersion(prefix: String, name: String, hash: String): Boolean = {
    getInfo(prefix, name) match {
      case Some(info) if info.versions.exists(_.hash == hash) =>
        val updatedInfo = info.copy(currentVersion = Some(hash))
        saveMetadata(prefix, name, updatedInfo)
        logger.info(s"Set current version for $prefix/$name to $hash")
        true
      case Some(_) =>
        logger.warn(s"Version $hash not found for $prefix/$name")
        false
      case None =>
        logger.warn(s"No mapping info found for $prefix/$name")
        false
    }
  }

  /**
   * Delete a specific version
   */
  def deleteVersion(prefix: String, name: String, hash: String): Boolean = {
    getInfo(prefix, name) match {
      case Some(info) =>
        info.versions.find(_.hash == hash) match {
          case Some(version) =>
            // Delete the file
            val versionFile = new File(new File(new File(new File(defaultMappingsDir, prefix), name), VERSIONS_DIR), version.filename)
            if (versionFile.exists()) {
              versionFile.delete()
            }

            // Update metadata
            val remainingVersions = info.versions.filterNot(_.hash == hash)
            val newCurrentVersion = if (info.currentVersion.contains(hash)) {
              remainingVersions.sortBy(_.timestamp.getMillis).lastOption.map(_.hash)
            } else {
              info.currentVersion
            }

            val updatedInfo = info.copy(versions = remainingVersions, currentVersion = newCurrentVersion)
            saveMetadata(prefix, name, updatedInfo)
            logger.info(s"Deleted version $hash for $prefix/$name")
            true

          case None =>
            logger.warn(s"Version $hash not found for $prefix/$name")
            false
        }
      case None =>
        logger.warn(s"No mapping info found for $prefix/$name")
        false
    }
  }

  /**
   * Update the display name for a named mapping
   */
  def updateDisplayName(prefix: String, name: String, displayName: String): Boolean = {
    getInfo(prefix, name) match {
      case Some(info) =>
        val updatedInfo = info.copy(displayName = displayName)
        saveMetadata(prefix, name, updatedInfo)
        true
      case None =>
        false
    }
  }

  /**
   * Get the current version hash for a named mapping
   */
  def getCurrentVersionHash(prefix: String, name: String): Option[String] = {
    getInfo(prefix, name).flatMap(_.currentVersion)
  }

  /**
   * Check if a mapping version with the given hash exists
   */
  def hasVersion(prefix: String, name: String, hash: String): Boolean = {
    getInfo(prefix, name).exists(_.versions.exists(_.hash == hash))
  }

  /**
   * Delete an entire named mapping and all its versions
   */
  def deleteMapping(prefix: String, name: String): Boolean = {
    val mappingDir = new File(new File(defaultMappingsDir, prefix), name)
    if (mappingDir.exists()) {
      FileUtils.deleteDirectory(mappingDir)
      logger.info(s"Deleted mapping $prefix/$name")
      true
    } else {
      false
    }
  }

  private def saveMetadata(prefix: String, name: String, info: NamedMapping): Unit = {
    val mappingDir = new File(new File(defaultMappingsDir, prefix), name)
    mappingDir.mkdirs()

    val metadataFile = new File(mappingDir, METADATA_FILE)
    val json = Json.prettyPrint(Json.toJson(info))
    FileUtils.writeStringToFile(metadataFile, json, "UTF-8")
  }

  // ============================================================================
  // Backward compatibility methods - deprecated, use named mapping methods instead
  // ============================================================================

  /**
   * @deprecated Use getInfo(prefix, name) instead
   */
  @deprecated("Use getInfo(prefix, name) instead", "2.0")
  def getInfo(prefix: String): Option[NamedMapping] = {
    // Return the first mapping for the prefix (usually "default")
    listMappingsForPrefix(prefix).headOption.flatMap(m => getInfo(prefix, m.name))
  }

  /**
   * @deprecated Use getXml(prefix, name, version) instead
   */
  @deprecated("Use getXml(prefix, name, version) instead", "2.0")
  def getXml(prefix: String, version: String): Option[String] = {
    // Try to get from "default" mapping first, then fall back to first available
    getXml(prefix, "default", version).orElse {
      listMappingsForPrefix(prefix).headOption.flatMap(m => getXml(prefix, m.name, version))
    }
  }
}
