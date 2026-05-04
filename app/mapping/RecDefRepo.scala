//===========================================================================
//    Copyright 2026 Delving B.V.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//===========================================================================

package mapping

import java.io.File
import java.security.MessageDigest

import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import play.api.Logger
import play.api.libs.json._

import scala.xml.XML

/**
 * Versioned storage for record-definition (recdef) XML files plus their
 * paired XSD validation files. Mirrors `DefaultMappingRepo`'s shape.
 *
 * On-disk layout (under `<orgRoot>/rec-defs/<prefix>/`):
 *   versions/
 *     <timestamp>_<hash>_<schemaVersion>.xml
 *     <timestamp>_<hash>_<schemaVersion>.xsd     (optional)
 *   metadata.json
 *
 * Where `<schemaVersion>` is derived from the recdef root attrs
 * (`<record-definition prefix="X" version="Y"/>` → `X_Y`).
 */
object RecDefRepo {

  private val logger = Logger(getClass)

  val VERSIONS_DIR = "versions"
  val METADATA_FILE = "metadata.json"
  val TIMESTAMP_FORMAT = DateTimeFormat.forPattern("yyyyMMdd_HHmmss")

  case class RecDefVersion(
    hash: String,
    schemaVersion: String,
    filename: String,
    xsdFilename: Option[String],
    hasXsd: Boolean,
    uploadedAt: DateTime,
    source: String,
    notes: Option[String]
  )

  case class PrefixMetadata(
    prefix: String,
    currentVersion: Option[String],
    versions: List[RecDefVersion]
  )

  case class RecDefVersionResolved(
    version: RecDefVersion,
    recordDefinitionFile: File,
    validationFileOpt: Option[File]
  )

  case class PrefixSummary(
    prefix: String,
    currentVersion: Option[String],
    versionCount: Int,
    latestUploadedAt: Option[DateTime]
  )

  implicit val dateTimeReads: Reads[DateTime] = Reads { json =>
    json.validate[String].map(s => DateTime.parse(s))
  }
  implicit val dateTimeWrites: Writes[DateTime] = Writes { dt =>
    JsString(dt.toString)
  }

  implicit val recDefVersionFormat: Format[RecDefVersion] = Json.format[RecDefVersion]
  implicit val prefixMetadataFormat: Format[PrefixMetadata] = Json.format[PrefixMetadata]
  implicit val prefixSummaryFormat: Format[PrefixSummary] = Json.format[PrefixSummary]

  def computeHash(content: String): String = {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(content.getBytes("UTF-8"))
    digest.map("%02x".format(_)).mkString.take(8)
  }

  /**
   * Parse the recdef XML to extract `<record-definition prefix="X" version="Y"/>`.
   * Returns Some("X_Y") on success, None if the root element is missing required attrs.
   */
  def parseSchemaVersion(xml: String): Option[String] = {
    try {
      val root = XML.loadString(xml)
      if (root.label != "record-definition") return None
      val prefix = (root \ "@prefix").text
      val version = (root \ "@version").text
      if (prefix.isEmpty || version.isEmpty) None
      else Some(s"${prefix}_${version}")
    } catch {
      case e: Exception =>
        logger.warn(s"Failed to parse recdef XML: ${e.getMessage}")
        None
    }
  }

  def generateFilename(timestamp: DateTime, hash: String, schemaVersion: String, ext: String): String =
    s"${TIMESTAMP_FORMAT.print(timestamp)}_${hash}_${schemaVersion}.${ext}"
}

class RecDefRepo(orgRoot: File) {

  import RecDefRepo._

  private val logger = Logger(getClass)

  val recDefsDir = new File(orgRoot, "rec-defs")

  // -------- public API --------

  def listPrefixes(): List[String] = {
    if (!recDefsDir.exists()) Nil
    else recDefsDir.listFiles()
      .filter(_.isDirectory)
      .filter(d => new File(d, METADATA_FILE).exists())
      .map(_.getName)
      .toList
      .sorted
  }

  def listVersions(prefix: String): List[RecDefVersion] = {
    readMetadata(prefix).map(_.versions).getOrElse(Nil)
  }

  def listSummaries(): List[PrefixSummary] = {
    listPrefixes().map { prefix =>
      val meta = readMetadata(prefix)
      PrefixSummary(
        prefix = prefix,
        currentVersion = meta.flatMap(_.currentVersion),
        versionCount = meta.map(_.versions.size).getOrElse(0),
        latestUploadedAt = meta.flatMap(_.versions.sortBy(_.uploadedAt.getMillis).lastOption.map(_.uploadedAt))
      )
    }
  }

  def getCurrent(prefix: String): Option[RecDefVersionResolved] = {
    readMetadata(prefix).flatMap { meta =>
      meta.currentVersion.flatMap(hash => resolveVersion(prefix, meta, hash))
    }
  }

  def getVersion(prefix: String, hash: String): Option[RecDefVersionResolved] = {
    readMetadata(prefix).flatMap(meta => resolveVersion(prefix, meta, hash))
  }

  /**
   * Persist a new version. If `hash` already exists for this prefix, returns the
   * existing version (idempotent re-upload). First version saved becomes current.
   *
   * Throws IllegalArgumentException if the recdef XML is malformed or missing
   * required `<record-definition prefix=... version=...>` attrs.
   */
  def saveVersion(
    prefix: String,
    recDefXml: String,
    xsdXmlOpt: Option[String],
    source: String,
    notes: Option[String]
  ): RecDefVersion = {
    val schemaVersion = parseSchemaVersion(recDefXml).getOrElse(
      throw new IllegalArgumentException(
        "RecDef XML missing required <record-definition prefix=... version=...> attributes"
      )
    )
    val xmlPrefix = schemaVersion.split("_", 2)(0)
    if (xmlPrefix != prefix) {
      logger.warn(s"Uploaded recdef declares prefix='$xmlPrefix' but route prefix='$prefix' — using route prefix")
    }

    val hash = computeHash(recDefXml)
    val existing = readMetadata(prefix)

    existing.flatMap(_.versions.find(_.hash == hash)) match {
      case Some(v) =>
        logger.info(s"RecDef hash $hash already exists for $prefix — returning existing version")
        v
      case None =>
        val timestamp = DateTime.now()
        val xmlFilename = generateFilename(timestamp, hash, schemaVersion, "xml")
        val xsdFilenameOpt = xsdXmlOpt.map(_ => generateFilename(timestamp, hash, schemaVersion, "xsd"))

        val versionsDir = new File(new File(recDefsDir, prefix), VERSIONS_DIR)
        versionsDir.mkdirs()

        FileUtils.writeStringToFile(new File(versionsDir, xmlFilename), recDefXml, "UTF-8")
        xsdXmlOpt.zip(xsdFilenameOpt).foreach { case (xsd, fn) =>
          FileUtils.writeStringToFile(new File(versionsDir, fn), xsd, "UTF-8")
        }

        val newVersion = RecDefVersion(
          hash = hash,
          schemaVersion = schemaVersion,
          filename = xmlFilename,
          xsdFilename = xsdFilenameOpt,
          hasXsd = xsdXmlOpt.isDefined,
          uploadedAt = timestamp,
          source = source,
          notes = notes
        )

        val priorMeta = existing.getOrElse(PrefixMetadata(prefix, None, Nil))
        val updatedMeta = priorMeta.copy(
          versions = priorMeta.versions :+ newVersion,
          currentVersion = priorMeta.currentVersion.orElse(Some(hash))
        )
        writeMetadata(prefix, updatedMeta)
        logger.info(s"Saved recdef version $prefix/$hash ($schemaVersion${if (xsdXmlOpt.isDefined) "" else " — no XSD"})")
        newVersion
    }
  }

  def setCurrent(prefix: String, hash: String): Boolean = {
    readMetadata(prefix) match {
      case Some(meta) if meta.versions.exists(_.hash == hash) =>
        writeMetadata(prefix, meta.copy(currentVersion = Some(hash)))
        logger.info(s"Set current recdef for $prefix to $hash")
        true
      case _ =>
        logger.warn(s"setCurrent: prefix=$prefix hash=$hash not found")
        false
    }
  }

  /** Refuses if hash is currently active. Returns false on missing or current. */
  def deleteVersion(prefix: String, hash: String): Boolean = {
    readMetadata(prefix) match {
      case Some(meta) if meta.currentVersion.contains(hash) =>
        logger.warn(s"deleteVersion refused: $prefix/$hash is currentVersion")
        false
      case Some(meta) =>
        meta.versions.find(_.hash == hash) match {
          case Some(v) =>
            val versionsDir = new File(new File(recDefsDir, prefix), VERSIONS_DIR)
            new File(versionsDir, v.filename).delete()
            v.xsdFilename.foreach(fn => new File(versionsDir, fn).delete())
            val updated = meta.copy(versions = meta.versions.filterNot(_.hash == hash))
            writeMetadata(prefix, updated)
            logger.info(s"Deleted recdef version $prefix/$hash")
            true
          case None =>
            logger.warn(s"deleteVersion: $prefix/$hash not found")
            false
        }
      case None =>
        logger.warn(s"deleteVersion: prefix=$prefix has no metadata")
        false
    }
  }

  // -------- migration --------

  /**
   * One-shot migration. Copy each prefix's record-definition XML (and paired
   * validation XSD if present) from the legacy factory dir into the new
   * versioned layout. Idempotent — skips any prefix already populated.
   * Source recorded as "migration_from_factory".
   */
  def migrateFromFactoryOnce(factoryDir: File): Unit = {
    if (!factoryDir.exists() || !factoryDir.isDirectory) {
      logger.info(s"No factory dir at $factoryDir — skipping migration")
      return
    }
    factoryDir.listFiles().filter(_.isDirectory).foreach { prefixDir =>
      val prefix = prefixDir.getName
      val alreadyMigrated = new File(new File(recDefsDir, prefix), METADATA_FILE).exists()
      if (alreadyMigrated) {
        logger.info(s"Skipping factory migration for $prefix — already populated")
      } else {
        val recDefFile = prefixDir.listFiles().find(_.getName.endsWith("_record-definition.xml"))
        val xsdFile = prefixDir.listFiles().find(_.getName.endsWith("_validation.xsd"))
        recDefFile match {
          case Some(rd) =>
            try {
              val recDefXml = FileUtils.readFileToString(rd, "UTF-8")
              val xsdOpt = xsdFile.map(f => FileUtils.readFileToString(f, "UTF-8"))
              val v = saveVersion(
                prefix = prefix,
                recDefXml = recDefXml,
                xsdXmlOpt = xsdOpt,
                source = "migration_from_factory",
                notes = Some(s"Migrated from $rd on ${DateTime.now()}")
              )
              logger.info(s"Migrated factory/$prefix → rec-defs/$prefix (hash=${v.hash})")
            } catch {
              case e: Exception =>
                logger.warn(s"Failed to migrate $prefixDir: ${e.getMessage}")
            }
          case None =>
            logger.warn(s"No *_record-definition.xml in $prefixDir — skipping")
        }
      }
    }
  }

  // -------- internal --------

  private def metadataFile(prefix: String): File =
    new File(new File(recDefsDir, prefix), METADATA_FILE)

  private def readMetadata(prefix: String): Option[PrefixMetadata] = {
    val f = metadataFile(prefix)
    if (!f.exists()) None
    else try {
      Json.parse(FileUtils.readFileToString(f, "UTF-8")).asOpt[PrefixMetadata]
    } catch {
      case e: Exception =>
        logger.error(s"Error reading metadata for prefix=$prefix: ${e.getMessage}")
        None
    }
  }

  private def writeMetadata(prefix: String, meta: PrefixMetadata): Unit = {
    val prefixDir = new File(recDefsDir, prefix)
    prefixDir.mkdirs()
    FileUtils.writeStringToFile(
      new File(prefixDir, METADATA_FILE),
      Json.prettyPrint(Json.toJson(meta)),
      "UTF-8"
    )
  }

  private def resolveVersion(prefix: String, meta: PrefixMetadata, hash: String): Option[RecDefVersionResolved] = {
    meta.versions.find(_.hash == hash).flatMap { v =>
      val versionsDir = new File(new File(recDefsDir, prefix), VERSIONS_DIR)
      val recDefFile = new File(versionsDir, v.filename)
      if (!recDefFile.exists()) {
        logger.warn(s"Recorded recdef file missing on disk: $recDefFile")
        None
      } else {
        val xsdFile = v.xsdFilename.map(fn => new File(versionsDir, fn)).filter(_.exists())
        Some(RecDefVersionResolved(v, recDefFile, xsdFile))
      }
    }
  }
}
