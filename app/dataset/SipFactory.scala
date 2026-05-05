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
package dataset

import java.io.{File, FileInputStream, FileOutputStream}
import java.util.zip.{GZIPOutputStream, ZipEntry, ZipOutputStream}

import dataset.SipRepo.FACTS_FILE
import dataset.Sip
import java.util.HashMap
import mapping.RecDefRepo
import org.apache.commons.io.{FileUtils, IOUtils}
import play.api.Logger
import play.api.libs.ws.WSClient
import triplestore.GraphProperties._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.xml.NodeSeq

/**
 * @author Gerald de Jong <gerald@delving.eu>
 */

object SipFactory {

  val RECORD_DEFINITION_SUFFIX = "_record-definition.xml"
  val VALIDATION_SUFFIX = "_validation.xsd"

  case class SipGenerationFacts
  (spec: String,
    prefix: String,
    name: String,
    provider: String,
    dataProvider: String,
    dataProviderURL: String,
    language: String,
    rights: String,
    edmType: String,
    dataType: String,
    orgId: String
    )

  object SipGenerationFacts {
    def apply(info: NodeSeq) = {
      val meta = info \ "metadata"
      val facts = new SipGenerationFacts(
        spec = (info \ "@name").text,
        prefix = (info \ "character" \ "prefix").text,
        name = (meta \ "name").text,
        provider = (meta \ "provider").text,
        dataProvider = (meta \ "dataProvider").text,
        dataProviderURL = (meta \ "dataProviderURL").text,
        language = (meta \ "language").text,
        rights = (meta \ "rights").text,
        edmType = (meta \ "edmType").text,
        dataType = (meta \ "type").text,
        orgId = (meta \ "orgId").text
      )
      val factsString =
        s"""NodeSeq->spec=${facts.spec}
           |name=${facts.name}
           |provider=${facts.provider}
           |dataProvider=${facts.dataProvider}
           |dataProviderURL=${facts.dataProviderURL}
           |language=${facts.language}
           |type=${facts.edmType}
           |dataType=${facts.dataType}}
           |orgId=${facts.orgId}
           |""".stripMargin
      System.out.println(factsString)
      facts
    }

    def apply(dsInfo: DsInfo) = {
      def info(prop: NXProp) = dsInfo.getLiteralProp(prop).getOrElse("")
      val facts = new SipGenerationFacts(
        spec = dsInfo.spec,
        prefix = info(datasetMapToPrefix),
        name = info(datasetName),
        provider = info(datasetAggregator),
        dataProvider = info(datasetOwner),
        dataProviderURL = info(datasetDataProviderURL),
        language = info(datasetLanguage),
        rights = info(datasetRights),
        edmType = info(edmType),
        dataType = info(datasetType),
        orgId =info(orgId) 
      )
      val factsString =
        s"""DsInfo->spec=${facts.spec}
           |name=${facts.name}
           |provider=${facts.provider}
           |dataProvider=${facts.dataProvider}
           |dataProviderURL=${facts.dataProviderURL}
           |language=${facts.language}
           |rights=${facts.rights}
           |type=${facts.edmType}
           |dataType=${facts.dataType}
           |orgId=${facts.orgId}
           |""".stripMargin
      System.out.println(factsString)
      facts
    }
  }

}

class SipFactory(factoryDir: File, recDefsRoot: File, rdfBaseUrl: String, wsApi: WSClient, orgId: String)(implicit ec: ExecutionContext) {

  private val logger = Logger(getClass)

  // RecDefRepo expects an org root under which it creates `rec-defs/`.
  // We pass `recDefsRoot` (typically the same orgRoot OrgContext owns).
  val recDefRepo = new RecDefRepo(recDefsRoot)

  // One-shot migration on first access. Idempotent.
  private lazy val migrated: Unit = recDefRepo.migrateFromFactoryOnce(factoryDir)

  lazy val prefixRepos: Seq[SipPrefixRepo] = {
    val _ = migrated
    recDefRepo.listPrefixes().flatMap { prefix =>
      recDefRepo.getCurrent(prefix) match {
        case Some(resolved) =>
          Some(new SipPrefixRepo(
            prefix = prefix,
            recordDefinition = resolved.recordDefinitionFile,
            validationOpt = resolved.validationFileOpt,
            schemaVersionsValue = resolved.version.schemaVersion,
            rdfBaseUrl = rdfBaseUrl,
            ws = wsApi,
            orgId = orgId
          ))
        case None =>
          logger.warn(s"No current rec-def for prefix=$prefix — skipping")
          None
      }
    }
  }

  def prefixRepo(prefix: String): Option[SipPrefixRepo] = prefixRepos.find(_.prefix == prefix)

  /**
   * Resolve a `SipPrefixRepo` for a specific rec-def version. None hash → use
   * the prefix's "current" version (same as `prefixRepo(prefix)`).
   * Returns None if the prefix is unknown or the requested hash is missing.
   */
  def prefixRepo(prefix: String, versionHashOpt: Option[String]): Option[SipPrefixRepo] = {
    val _ = migrated
    versionHashOpt match {
      case None | Some("") => prefixRepo(prefix)
      case Some(hash) =>
        recDefRepo.getVersion(prefix, hash).map { resolved =>
          new SipPrefixRepo(
            prefix = prefix,
            recordDefinition = resolved.recordDefinitionFile,
            validationOpt = resolved.validationFileOpt,
            schemaVersionsValue = resolved.version.schemaVersion,
            rdfBaseUrl = rdfBaseUrl,
            ws = wsApi,
            orgId = orgId
          )
        }
    }
  }

}

class SipPrefixRepo(
  val prefix: String,
  val recordDefinition: File,
  val validationOpt: Option[File],
  schemaVersionsValue: String,
  rdfBaseUrl: String,
  ws: WSClient,
  orgId: String
)(implicit ec: ExecutionContext) {

  private val logger = Logger(getClass)

  import dataset.SipFactory._

  /** Throws if no XSD is available. Use validationOpt to test presence first. */
  lazy val validation: File = validationOpt.getOrElse(
    throw new RuntimeException(s"No validation XSD available for prefix=$prefix")
  )

  val schemaVersions: String = schemaVersionsValue

  def addFactsEntry(facts: SipGenerationFacts, zos: ZipOutputStream): Unit = {
    zos.putNextEntry(new ZipEntry(FACTS_FILE))
    val factsString =
      s"""spec=${facts.spec}
         |name=${facts.name}
         |provider=${facts.provider}
         |dataProvider=${facts.dataProvider}
         |dataProviderURL=${facts.dataProviderURL}
         |language=${facts.language}
         |schemaVersions=$schemaVersions
         |rights=${facts.rights}
         |type=${facts.edmType}
         |dataType=${facts.dataType}
         |baseUrl=${rdfBaseUrl}
         |orgId=${orgId}
         |""".stripMargin
    zos.write(factsString.getBytes("UTF-8"))
    zos.closeEntry()
  }

  def toMap(facts: SipGenerationFacts): HashMap[String,String] = {
    val map = new HashMap[String, String]()
    map.put("spec", facts.spec)
    map.put("name", facts.name)
    map.put("provider", facts.provider)
    map.put("dataProvider", facts.dataProvider)
    map.put("dataProviderURL", facts.dataProviderURL)
    map.put("language", facts.language)
    map.put("schemaVersions", schemaVersions)
    map.put("rights", facts.rights)
    map.put("baseUrl", rdfBaseUrl)
    map.put("orgId", facts.orgId)
    map.put("type", facts.edmType)
    map.put("dataType", facts.dataType)
    map
  }

  private def differenceWithSchemasDelvingEu(file: File): Option[String] = {
    val fo :Future[Option[String]] = ws.url(s"http://schemas.delving.eu/$prefix/${file.getName}").get().map { response =>
      val fileLines = FileUtils.readFileToString(file).trim.split("\n")
      val onlineLines = response.body.trim.split("\n")
      if (fileLines.size != onlineLines.size) {
        Some(s"$file has ${fileLines.size} lines, while online there are ${onlineLines.size}")
      }
      else {
        val comparisons = fileLines.zip(onlineLines)
        val diffs = comparisons.filter(c => c._1.trim != c._2.trim)
        if (diffs.nonEmpty) {
          Some(diffs.take(3).map(c => s"[${c._1.trim}] != [${c._2.trim}]").mkString("\n"))
        }
        else {
          None
        }
      }
    }
  Await.result(fo, 30.seconds)
  }

  def compareWithSchemasDelvingEu() = {
    differenceWithSchemasDelvingEu(recordDefinition).map(diff => logger.warn(s"Rec Def Discrepancy: $diff"))
    validationOpt.foreach { v =>
      differenceWithSchemasDelvingEu(v).map(diff => logger.warn(s"Validation Discrepancy: $diff"))
    }
  }

  def initiateSipZip(sipFile: File, sourceXmlFile: File, facts: SipGenerationFacts, customMappingXml: Option[String] = None) = {
    val fos = new FileOutputStream(sipFile)
    try {
      val zos = new ZipOutputStream(fos)
      try {
        addFactsEntry(facts, zos)

        // record definition + validation. Entry name uses the legacy
        // `<schemaVersions>_record-definition.xml` / `_validation.xsd`
        // shape so SIP-Creator (which expects that filename pattern) can
        // still find them — even though the repo stores files under
        // `<timestamp>_<hash>_<schemaVersion>.xml`.
        def copyIn(fileToCopy: File, entryName: String) = {
          zos.putNextEntry(new ZipEntry(entryName))
          val fileIn = new FileInputStream(fileToCopy)
          try {
            IOUtils.copy(fileIn, zos)
          } finally {
            fileIn.close()
          }
          zos.closeEntry()
        }
        copyIn(recordDefinition, s"${schemaVersions}${RECORD_DEFINITION_SUFFIX}")
        validationOpt.foreach(v => copyIn(v, s"${schemaVersions}${VALIDATION_SUFFIX}"))

        // Include mapping if provided (e.g., from default mappings). Rewrite
        // the `<facts>` block from the target dataset's facts so the template's
        // stale spec / empty dataProviderURL don't leak into the new SIP.
        customMappingXml.foreach { mappingXml =>
          val mappingFileName = s"mapping_$prefix.xml"
          logger.info(s"Including default mapping in new SIP: $mappingFileName")
          zos.putNextEntry(new ZipEntry(mappingFileName))
          val bytesOpt = scala.util.Try(Sip.loadRecDefTree(recordDefinition)).toOption.flatMap { tree =>
            Sip.rewriteFactsInMappingXml(mappingXml, tree, toMap(facts))
          }
          bytesOpt match {
            case Some(bytes) => zos.write(bytes)
            case None =>
              logger.warn(s"Unable to rewrite facts for $mappingFileName; writing verbatim")
              zos.write(mappingXml.getBytes("UTF-8"))
          }
          zos.closeEntry()
        }

        // hints.txt - required for SIP reading
        zos.putNextEntry(new ZipEntry(SipRepo.HINTS_FILE))
        zos.write("pockets=true\n".getBytes("UTF-8"))
        zos.closeEntry()

        // source, gzipped
        zos.putNextEntry(new ZipEntry(SipRepo.SOURCE_FILE))
        val gzipOut = new GZIPOutputStream(zos)
        try {
          val sourceIn = new FileInputStream(sourceXmlFile)
          try {
            IOUtils.copy(sourceIn, gzipOut)
          } finally {
            sourceIn.close()
          }
        } finally {
          gzipOut.close()
        }
      } finally {
        zos.close()
      }
    } catch {
      case e: Exception =>
        fos.close()
        throw e
    }
  }
}
