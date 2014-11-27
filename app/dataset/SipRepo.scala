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

import java.io._
import java.util.zip.{GZIPOutputStream, ZipEntry, ZipFile, ZipOutputStream}

import eu.delving.groovy._
import eu.delving.metadata._
import org.apache.commons.io.{FileUtils, IOUtils}
import org.joda.time.DateTime
import org.w3c.dom.Element
import play.api.Logger
import record.PocketParser._
import services.StringHandling.urlEncodeValue
import services.Temporal

import scala.collection.JavaConversions._
import scala.io.Source

/**
 * A repository of sip files which knows everything about what a sip means, revealing all of the contained
 * attributes from the contained property files and able to produce a SipMapper which can transform pockets
 * of data according to the SIP-Creator mappings.
 *
 * @author Gerald de Jong <gerald@delving.eu>
 */

object SipRepo {

  val SIP_SOURCE_RECORD_ROOT = "/delving-sip-source/input"
  val SIP_SOURCE_UNIQUE_ID = "/delving-sip-source/input/@id"

  def apply(datasetName: String, rdfAboutPrefix: String, home: File): SipRepo = new SipRepo(datasetName, rdfAboutPrefix, home)
}

class SipRepo(datasetName: String, rdfAboutPrefix: String, home: File) {

  def createSipZipFile(sipZipFileName: String) = {
    home.mkdir()
    new File(home, sipZipFileName)
  }

  def listSips: Seq[Sip] = {
    if (home.exists()) {
      val zipFiles = home.listFiles().filter(_.getName.endsWith("zip"))
      zipFiles.sortBy(_.getName).reverse.map(Sip(datasetName, rdfAboutPrefix, _))
    }
    else Seq.empty[Sip]
  }

  def latestSipOpt: Option[Sip] = listSips.headOption

}

object Sip {

  case class SipMapping(datasetName: String, prefix: String, version: String, recDefTree: RecDefTree, validationXSD: String, recMapping: RecMapping) {
    def namespaces: Map[String, String] = recDefTree.getRecDef.namespaces.map(ns => ns.prefix -> ns.uri).toMap
  }

  trait SipMapper {

    val datasetName: String

    val prefix: String

    def map(pocket: Pocket): Option[Pocket]
  }

  val PrefixVersion = "(.*)_(.*)".r

  def apply(datasetName: String, rdfAboutPrefix: String, zipFile: File) = new Sip(datasetName, rdfAboutPrefix, zipFile)

  val XMLNS = "http://www.w3.org/2000/xmlns/"
  val RDF_ROOT_TAG: String = "RDF"
  val RDF_RECORD_TAG: String = "Description"
  val RDF_ABOUT_ATTRIBUTE: String = "about"
  val RDF_PREFIX: String = "rdf"
  val RDF_URI: String = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"

  //  val NAMESPACES = Map(
  //    "rdf" -> "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
  //    "geo" -> "http://www.w3.org/2003/01/geo/wgs84_pos#",
  //    "skos" -> "http://www.w3.org/2004/02/skos/core#",
  //    "rdfs" -> "http://www.w3.org/2000/01/rdf-schema#",
  //    "cc" -> "http://creativecommons.org/ns#",
  //    "owl" -> "http://www.w3.org/2002/07/owl#",
  //    "foaf" -> "http://xmlns.com/foaf/0.1/",
  //    "dbpedia-owl" -> "http://dbpedia.org/ontology/",
  //    "dbprop" -> "http://dbpedia.org/property/"
  //  )

}

class Sip(val datasetName: String, rdfAboutPrefix: String, val file: File) {

  import dataset.Sip._

  lazy val zipFile = new ZipFile(file)

  lazy val entries = zipFile.entries().map(entry => entry.getName -> entry).toMap

  def readMap(propertyFileName: String): Map[String, String] = {
    entries.get(propertyFileName).map { entry =>
      val inputStream = zipFile.getInputStream(entry)
      val lines = Source.fromInputStream(inputStream, "UTF-8").getLines()
      lines.flatMap { line =>
        val equals = line.indexOf("=")
        if (equals < 0) None else Some(line.substring(0, equals).trim -> line.substring(equals + 1).trim)
      }.toMap
    } getOrElse (throw new RuntimeException(s"No entry for $propertyFileName"))
  }

  lazy val facts = readMap("narthex_facts.txt")

  def fact(name: String): Option[String] = facts.get(name)

  lazy val spec = fact("spec")
  lazy val name = fact("name")
  lazy val orgId = fact("orgId")
  lazy val provider = fact("provider")
  lazy val dataProvider = fact("dataProvider")
  lazy val country = fact("country")
  lazy val language = fact("language")
  lazy val rights = fact("rights")
  lazy val schemaVersions = fact("schemaVersions")

  lazy val hints = readMap("hints.txt")

  private def hint(name: String): Option[String] = hints.get(name)

  lazy val harvestUrl = hint("harvestUrl")
  lazy val harvestSpec = hint("harvestSpec")
  lazy val harvestPrefix = hint("harvestPrefix")
  lazy val recordCount = hint("recordCount")
  lazy val recordRootPath = hint("recordRootPath")
  lazy val uniqueElementPath = hint("uniqueElementPath")

  lazy val schemaVersionOpt: Option[String] = schemaVersions.flatMap(commas => commas.split(" *,").headOption)

  private def file(sipContentFileName: String): String = {
    entries.get(sipContentFileName).map { entry =>
      val inputStream = zipFile.getInputStream(entry)
      Source.fromInputStream(inputStream, "UTF-8").mkString
    } getOrElse (throw new RuntimeException(s"Unable to read file $sipContentFileName from $file"))
  }

  private def recDefTree(sipContentFileName: String): RecDefTree = {
    entries.get(sipContentFileName).map { entry =>
      val inputStream = zipFile.getInputStream(entry)
      val recDef = RecDef.read(inputStream)
      RecDefTree.create(recDef)
    } getOrElse (throw new RuntimeException(s"Unable to read rec def $sipContentFileName from $file"))
  }

  private def recMapping(sipContentFileName: String, recDefTree: RecDefTree): RecMapping = {
    entries.get(sipContentFileName).map { entry =>
      val inputStream = zipFile.getInputStream(entry)
      val mapping = RecMapping.read(inputStream, recDefTree)
      // in the case of a harvest sip, we strip off /metadata/<recordRoot>
      if (harvestUrl.isDefined) mapping.getNodeMappings.foreach { nodeMapping =>
        val inputPath = nodeMapping.inputPath.toString
        if (inputPath.startsWith("/input")) {
          recordRootPath.map {
            case "/harvest/OAI-PMH/ListRecords/record" =>
              //  <ListRecords><record><metadata>
              //      recordRootPath=/harvest/OAI-PMH/ListRecords/record
              //      uniqueElementPath=/harvest/OAI-PMH/ListRecords/record/metadata/arno:document/arno:document-admin/arno:doc_id
              nodeMapping.inputPath = Path.create(inputPath.replaceFirst("/metadata/[^/]*/", "/"))
          }
          //          println(s"$inputPath => ${nodeMapping.inputPath}")
        }
      }
      mapping
    } getOrElse (throw new RuntimeException(s"Unable to read rec def $sipContentFileName from $file"))
  }

  lazy val sipMappingOpt: Option[SipMapping] = schemaVersionOpt.map { schemaVersion =>
    val PrefixVersion(prefix, version) = schemaVersion
    val tree = recDefTree(s"${schemaVersion}_record-definition.xml")
    SipMapping(
      datasetName = datasetName,
      prefix = prefix,
      version = version,
      recDefTree = tree,
      validationXSD = "when you want to validate" /* file(s"${schemaVersion}_validation.xsd")*/ ,
      recMapping = recMapping(s"mapping_$prefix.xml", tree)
    )
  }

  def copySourceToTempFile: Option[File] = {
    entries.get("source.xml.gz").map { sourceXmlGz =>
      val sourceFile = new File(file.getParentFile, "source.xml.gz")
      val inputStream = zipFile.getInputStream(sourceXmlGz)
      FileUtils.copyInputStreamToFile(inputStream, sourceFile)
      inputStream.close()
      sourceFile
    }
  }

  class MappingEngine(sipMapping: SipMapping) extends SipMapper {
    val now = new DateTime
    val groovy = new GroovyCodeResource(getClass.getClassLoader)
    val serializer = new XmlSerializer
    val namespaces = sipMapping.recDefTree.getRecDef.namespaces.map(ns => ns.prefix -> ns.uri).toMap
    val factory = new MetadataRecordFactory(namespaces)
    val runner = new MappingRunner(groovy, sipMapping.recMapping, null, false)

    override val datasetName = sipMapping.datasetName

    override val prefix: String = sipMapping.prefix

    override def map(pocket: Pocket): Option[Pocket] = {
      try {
        val metadataRecord = factory.metadataRecordFrom(pocket.id, pocket.recordXml, false)
        val result = new MappingResultImpl(serializer, pocket.id, runner.runMapping(metadataRecord), runner.getRecDefTree).resolve
        val root = result.root().asInstanceOf[Element]
        val doc = root.getOwnerDocument
        root.removeAttribute("xsi:schemaLocation")
        val pocketElement = doc.createElement("pocket")
        pocketElement.setAttribute("id", pocket.id)
        pocketElement.setAttribute("hash", pocket.hash)
        pocketElement.setAttribute("mod", Temporal.timeToString(now))
        val cn = root.getChildNodes
        val kids = for (index <- 0 to (cn.getLength - 1)) yield cn.item(index)
        val rdfElement = doc.createElementNS(RDF_URI, s"$RDF_PREFIX:$RDF_RECORD_TAG")
        val prefix = if (rdfAboutPrefix.endsWith("/"))
          rdfAboutPrefix.substring(0, rdfAboutPrefix.length - 1)
        else
          rdfAboutPrefix
        val rdfAbout = s"$prefix/$datasetName/${urlEncodeValue(pocket.id)}"
        rdfElement.setAttributeNS(RDF_URI, s"$RDF_PREFIX:$RDF_ABOUT_ATTRIBUTE", rdfAbout)
        kids.foreach(rdfElement.appendChild)
        pocketElement.appendChild(rdfElement)
        // the serializer gives us <?xml..?>
        val xml = serializer.toXml(pocketElement, true).replaceFirst("<[?].*[?]>\n", "")
        Some(Pocket(pocket.id, pocket.hash, xml, sipMapping.namespaces + (RDF_PREFIX -> RDF_URI)))
      } catch {
        case discard: DiscardRecordException =>
          Logger.info("Discarded record " + pocket.id, discard)
          None
      }
    }
  }

  def createSipMapper: Option[SipMapper] = sipMappingOpt.map(new MappingEngine(_))

  def copyWithSourceTo(sipFile: File, sourceFile: File) = {
    val entryList: List[ZipEntry] = zipFile.entries().toList
    // todo: filter out "narthex_facts.txt" and add "pocket_facts.txt"?
    val toCopy = entryList.filter(entry => entry.getName != "source.xml.gz")
    val zos = new ZipOutputStream(new FileOutputStream(sipFile))
    toCopy.foreach { entry =>
      zos.putNextEntry(entry)
      val is = zipFile.getInputStream(entry)
      IOUtils.copy(is, zos)
      zos.closeEntry()
    }
    zos.putNextEntry(new ZipEntry("source.xml.gz"))
    val gzipOut = new GZIPOutputStream(zos)
    val sourceIn = new FileInputStream(sourceFile)
    IOUtils.copy(sourceIn, gzipOut)
    gzipOut.close()
    zos.close()
  }

}