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
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.Validator

import eu.delving.XMLToolFactory
import eu.delving.groovy._
import eu.delving.metadata._
import eu.delving.schema.SchemaVersion
import org.apache.commons.io.{FileUtils, IOUtils}
import org.joda.time.DateTime
import org.w3c.dom.Element
import play.api.Logger
import record.PocketParser._
import services.StringHandling.urlEncodeValue

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

  val SOURCE_FILE = "source.xml.gz"
  val FACTS_FILE = "narthex_facts.txt"
  val HINTS_FILE = "hints.txt"
  val MAX_ZIP_COUNT = 6
  val SchemaVersionsPattern = "schemaVersions=(.*)".r
  val SourcePattern = "source.*".r
  val MappingPattern = "mapping_.*".r
  val RecordDefinitionPattern = ".*_record-definition.xml".r
  val ValidationPattern = ".*_validation.xsd".r


  val SIP_SOURCE_RECORD_ROOT = "/delving-sip-source/input"
  val SIP_SOURCE_UNIQUE_ID = "/delving-sip-source/input/@id"
  val SIP_SOURCE_DEEP_RECORD_CONTAINER = Some(SIP_SOURCE_RECORD_ROOT)

  val SIP_EXTENSION = ".sip.zip"

  case class AvailableSip(file: File) {
    val n = file.getName
    if (!n.endsWith(SIP_EXTENSION)) throw new RuntimeException(s"Strange file name $file")
    val datasetName = n.substring(0, n.indexOf("__"))
    val dateTime = new DateTime(file.lastModified())
  }

}

class SipRepo(home: File, spec: String, naveDomain: String) {

  def createSipZipFile(sipZipFileName: String) = {
    home.mkdir()
    new File(home, sipZipFileName)
  }

  def listSips: Seq[Sip] = {
    if (home.exists()) {
      val filesLastToFirst = home.listFiles().filter(_.getName.endsWith(".sip.zip")).sortBy(_.lastModified())
      val filesLimited =
        if (filesLastToFirst.size <= SipRepo.MAX_ZIP_COUNT)
          filesLastToFirst
        else {
          filesLastToFirst.head.delete()
          filesLastToFirst.tail
        }
      filesLimited.reverse.map(Sip(spec, naveDomain, _))
    }
    else Seq.empty[Sip]
  }

  def latestSipOpt: Option[Sip] = listSips.headOption

}

object Sip {

  case class SipMapping(spec: String, prefix: String, version: String,
                        recDefTree: RecDefTree, validatorOpt: Option[Validator],
                        recMapping: RecMapping, extendWithRecord: Boolean) {

    def namespaces: Map[String, String] = recDefTree.getRecDef.namespaces.map(ns => ns.prefix -> ns.uri).toMap

    def fileName = s"mapping_$prefix.xml"
  }

  trait SipMapper {

    val datasetName: String

    val prefix: String

    def map(pocket: Pocket): Option[Pocket]
  }

  val PrefixVersion = "(.*)_(.*)".r

  def apply(datasetName: String, naveDomain: String, zipFile: File) = new Sip(datasetName, naveDomain, zipFile)

  val XMLNS = "http://www.w3.org/2000/xmlns/"
  val RDF_ROOT_TAG: String = "RDF"
  val RDF_RECORD_TAG: String = "Description"
  val RDF_ABOUT_ATTRIBUTE: String = "about"
  val RDF_PREFIX: String = "rdf"
  val RDF_URI: String = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"

  val INPUT_METADATA = "/input/metadata/"

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

class Sip(val dsInfoSpec: String, naveDomain: String, val file: File) {

  import dataset.Sip._
  import dataset.SipRepo._

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

  lazy val facts = readMap(FACTS_FILE)

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

  lazy val hints = readMap(HINTS_FILE)

  private def hint(name: String): Option[String] = hints.get(name)

  lazy val harvestUrl = hint("harvestUrl")
  lazy val harvestSpec = hint("harvestSpec")
  lazy val harvestPrefix = hint("harvestPrefix")
  lazy val recordCount = hint("recordCount")
  lazy val recordRootPath = hint("recordRootPath")
  lazy val uniqueElementPath = hint("uniqueElementPath")
  lazy val pockets = hint("pockets")

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

  private def multipleTagsWithinMetadata(mapping: RecMapping) = {
    val inputPaths = mapping.getNodeMappings.map(_.inputPath.toString).filter(_.startsWith(INPUT_METADATA))
    val nextTags = inputPaths.map(_.substring(INPUT_METADATA.length)).map { remainder =>
      val slash = remainder.indexOf('/')
      if (slash > 0) remainder.substring(0, slash) else remainder
    }
    nextTags.distinct.size > 1
  }

  private def recMapping(sipContentFileName: String, recDefTree: RecDefTree): (RecMapping, Boolean) = {
    entries.get(sipContentFileName).map { entry =>
      val inputStream = zipFile.getInputStream(entry)
      val mapping = RecMapping.read(inputStream, recDefTree)
      // now we look at whether adjustments must be made for sip-zips from the bridge sip-creator version
      // this stuff can be removed when migration is definitively over
      val extendWithRecord = multipleTagsWithinMetadata(mapping)
      // in the case of a harvest sip, we strip off /metadata/<recordRoot>
      if (!pockets.isDefined) {
        def fixGroovyArrays(nodeMapping: NodeMapping) = {
          val code = nodeMapping.getGroovyCode
          if (code != null) {
            val fixed = code.replaceAll("_M4", "_M5").replaceAll("_M3", "_M4").replaceAll("_M2", "_M3").replaceAll("_M1", "_M2")
            if (fixed != code) Logger.info(s"Fixed _M? array references\nBefore: $code\nAfter:$fixed")
            nodeMapping.setGroovyCode(fixed)
          }
        }
        if (harvestUrl.isDefined) {
          val norvegianaCheat = recordRootPath == Some("/delving-harvest/input/metadata/abm:record")
          mapping.getNodeMappings.foreach { nodeMapping =>
            val inputPath = nodeMapping.inputPath.toString
            val changeOpt = Option(inputPath).find(_.startsWith("/input/")).map { path =>
              if (extendWithRecord) {
                // case B: /input/metadata/dc:description => /input/record/metadata/dc:description
                s"/input/$SIP_RECORD_TAG/metadata/" + inputPath.substring(INPUT_METADATA.length)
              } else if (norvegianaCheat) {
                // case /input/? => /input/abm:record/?
                inputPath.replaceFirst("/input/([^/]+)", "/input/abm:record/$1")
              } else {
                // case /input/metadata/oai_dc:dc/dc:format => /input/oai_dc:dc/dc:format
                inputPath.replaceFirst("/input/metadata/([^/]+)/", "/input/$1/")
              }
            }
            changeOpt.foreach { path =>
              nodeMapping.inputPath = Path.create(path)
              Logger.debug(s"Adjust input path: $inputPath => $path")
            }
            fixGroovyArrays(nodeMapping)
          }
        }
        else {
          mapping.getNodeMappings.foreach { nodeMapping =>
            val inputPath = nodeMapping.inputPath.toString
            if (inputPath.startsWith("/input")) {
              // take input as the record root
              nodeMapping.inputPath = Path.create(inputPath.replaceFirst("/input/", s"/input/$SIP_RECORD_TAG/"))
              Logger.debug(s"Adjust input path: $inputPath => ${nodeMapping.inputPath}")
            }
            fixGroovyArrays(nodeMapping)
          }

          // todo: this is an attempt to add a root mapping
          //          val nm = new NodeMapping
          //          nm.inputPath = Path.create("/input")
          //          nm.outputPath = Path.create("/edm:RDF")
          //          mapping.getNodeMappings.add(0, nm)
          // todo: this is an attempt to add a root mapping

        }
      }
      (mapping, extendWithRecord)
    } getOrElse (throw new RuntimeException(s"Unable to read rec def $sipContentFileName from $file"))
  }

  private class ResolverContext extends CachedResourceResolver.Context {

    override def get(url: String): String = {
      throw new RuntimeException
    }

    override def file(systemId: String): File = {
      throw new RuntimeException
    }
  }

  private def validator(sipContentFileName: String, prefix: String): Option[Validator] = {
    entries.get(sipContentFileName).map { entry =>
      val inputStream = zipFile.getInputStream(entry)
      val schemaFactory = XMLToolFactory.schemaFactory(prefix)
      val context = new ResolverContext()
      val resolver = new CachedResourceResolver(context)
      schemaFactory.setResourceResolver(resolver)
      val source: StreamSource = new StreamSource(inputStream)
      val schema = schemaFactory.newSchema(source)
      schema.newValidator()
    }
  }

  lazy val sipMappingOpt: Option[SipMapping] = schemaVersionOpt.map { schemaVersion =>
    val PrefixVersion(prefix, version) = schemaVersion
    val tree = recDefTree(s"${schemaVersion}_record-definition.xml")
    val (mapping, extendWithRecord) = recMapping(s"mapping_$prefix.xml", tree)
    SipMapping(
      spec = dsInfoSpec,
      prefix = prefix,
      version = version,
      recDefTree = tree,
      recMapping = mapping,
      //      validatorOpt = validator(s"${schemaVersion}_validation.xsd", prefix),
      validatorOpt = None,
      extendWithRecord = extendWithRecord
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

//    println(s"input paths:\n${sipMapping.recMapping.getNodeMappings.toList.map(nm => s"${nm.inputPath} -> ${nm.outputPath}").mkString("\n")}")
//    println(runner.getCode)

    override val datasetName = sipMapping.spec

    override val prefix: String = sipMapping.prefix

    override def map(pocket: Pocket): Option[Pocket] = {
      try {
        val metadataRecord = factory.metadataRecordFrom(pocket.recordXml, pocket.id)
        val result = new MappingResultImpl(serializer, pocket.id, runner.runMapping(metadataRecord), runner.getRecDefTree).resolve
        val root = result.root().asInstanceOf[Element]
        val doc = root.getOwnerDocument
        root.removeAttribute("xsi:schemaLocation")
        val cn = root.getChildNodes
        val kids = for (index <- 0 to (cn.getLength - 1)) yield cn.item(index)
        val rdfAbout = s"$naveDomain/resource/graph/$datasetName/${urlEncodeValue(pocket.id)}"

        val rootNode = prefix match {
          case "edm" =>
            val rdfWrapper = doc.createElementNS(RDF_URI, s"$RDF_PREFIX:$RDF_ROOT_TAG")
            kids.foreach(rdfWrapper.appendChild)
            rdfWrapper

          case _ =>
            val rdfElement = doc.createElementNS(RDF_URI, s"$RDF_PREFIX:$RDF_RECORD_TAG")
            rdfElement.setAttributeNS(RDF_URI, s"$RDF_PREFIX:$RDF_ABOUT_ATTRIBUTE", rdfAbout)
            kids.foreach(rdfElement.appendChild)
            rdfElement
        }

        // the serializer gives us <?xml..?>
        val xml = serializer.toXml(rootNode, true).replaceFirst("<[?].*[?]>\n", "")
        val validationExceptionOpt: Option[Exception] = sipMapping.validatorOpt.flatMap { validator =>
          try {
            validator.validate(new DOMSource(root))
            None
          }
          catch {
            case e: Exception => Some(e)
          }
        }
        validationExceptionOpt.map { validationException =>
          Logger.info("Invalid record " + pocket.id)
          None
        } getOrElse {
          Some(Pocket(rdfAbout, xml, sipMapping.namespaces + (RDF_PREFIX -> RDF_URI)))
        }
      } catch {
        case discard: DiscardRecordException =>
          Logger.info("Discarded record " + pocket.id)
          None
      }
    }
  }

  def createSipMapper: Option[SipMapper] = sipMappingOpt.map(new MappingEngine(_))

  def copyWithSourceTo(sipFile: File, sourceXmlFile: File, sipPrefixRepoOpt: Option[SipPrefixRepo]) = {
    val zos = new ZipOutputStream(new FileOutputStream(sipFile))

    zipFile.entries.foreach { entry =>

      def copyEntry(): Unit = {
        zos.putNextEntry(new ZipEntry(entry.getName))
        val is = zipFile.getInputStream(entry)
        IOUtils.copy(is, zos)
        zos.closeEntry()
      }

      def copyFileIn(sourceFile: File, fileName: String, gzip: Boolean) = {
        zos.putNextEntry(new ZipEntry(fileName))
        val in = new FileInputStream(sourceFile)
        if (gzip) {
          val out = new GZIPOutputStream(zos)
          IOUtils.copy(in, out)
          out.finish()
        }
        else {
          IOUtils.copy(in, zos)
        }
        in.close()
        zos.closeEntry()
      }

      entry.getName match {

        case MappingPattern() =>
          Logger.info(s"Mapping: ${entry.getName}")
          sipMappingOpt.map { sipMapping =>
            zos.putNextEntry(new ZipEntry(sipMapping.fileName))
            // if there is a new schema version, set it
            sipPrefixRepoOpt.foreach(sipPrefix => sipMapping.recMapping.setSchemaVersion(new SchemaVersion(sipPrefix.schemaVersions)))
            RecMapping.write(zos, sipMapping.recMapping)
            zos.closeEntry()
          }

        case RecordDefinitionPattern() =>
          Logger.info(s"Record definition: ${entry.getName}")
          sipPrefixRepoOpt.map(_.recordDefinition).map(rd => copyFileIn(rd, rd.getName, gzip = false)).getOrElse(copyEntry())

        case ValidationPattern() =>
          Logger.info(s"Validation: ${entry.getName}")
          sipPrefixRepoOpt.map(_.validation).map(va => copyFileIn(va, va.getName, gzip = false)).getOrElse(copyEntry())

        case SourcePattern() =>
          Logger.info(s"Source: ${entry.getName}")
          copyFileIn(sourceXmlFile, entry.getName, gzip = true)

        case FACTS_FILE =>
          Logger.info(s"Facts: ${entry.getName}")
          sipPrefixRepoOpt.map(_.schemaVersions).map { schemaVersions =>
            val in = zipFile.getInputStream(entry)
            val lines = Source.fromInputStream(in, "UTF-8").getLines()
            val replaced = lines.map {
              case SchemaVersionsPattern(value) => s"schemaVersions=$schemaVersions"
              case other => other
            }
            zos.putNextEntry(new ZipEntry(FACTS_FILE))
            replaced.foreach(line => zos.write(s"$line\n".getBytes("UTF-8")))
            zos.closeEntry()
          }.getOrElse(copyEntry())

        case entryName =>
          Logger.info(s"Verbatim: ${entry.getName}")
          copyEntry()
      }
    }

    zos.close()
  }

}