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
package services

import java.io._
import java.util.zip.ZipFile

import eu.delving.groovy.{GroovyCodeResource, MappingRunner, MetadataRecordFactory, XmlSerializer}
import eu.delving.metadata._
import record.PocketParser.Pocket

import scala.collection.JavaConversions._
import scala.io.Source

/**
 * A repository of sip files which knows everything about what a sip means, revealing all of the contained
 * attributes from the contained property files and able to produce a SipMapper which can transform pockets
 * of data according to the SIP-Creator mappings.
 *
 * @author Gerald de Jong <gerald@delving.eu>
 */

class SipRepo(home: File) {
  home.mkdir()

  def latestSIPFile: Option[SipFile] = {
    val zipFIles = home.listFiles().filter(_.getName.endsWith("zip"))
    zipFIles.sortBy(_.getName).lastOption.map(SipFile(_))
  }

}

object SipFile {

  case class SipMapping(prefix: String, version: String, recDefTree: RecDefTree, validationXSD: String, recMapping: RecMapping)

  trait SipMapper {
    val prefix: String

    def map(pocket: Pocket): Pocket
  }

  val PrefixVersion = "(.*)_(.*)".r

  def apply(zipFile: File) = new SipFile(zipFile)

}

class SipFile(file: File) {

  import services.SipFile._

  lazy val zipFile = new ZipFile(file)

  lazy val entries = zipFile.entries().map(entry => entry.getName -> entry).toMap

  def readMap(propertyFileName: String): Option[Map[String, String]] = {
    entries.get(propertyFileName).map { entry =>
      val inputStream = zipFile.getInputStream(entry)
      val lines = Source.fromInputStream(inputStream, "UTF-8").getLines()
      lines.flatMap { line =>
        val equals = line.indexOf("=")
        if (equals < 0) None else Some(line.substring(0, equals).trim -> line.substring(equals + 1).trim)
      }.toMap
    }
  }

  lazy val facts = readMap("dataset_facts.txt")

  def fact(name: String): Option[String] = facts.flatMap(_.get(name))

  lazy val spec = fact("spec")
  lazy val orgId = fact("orgId")
  lazy val provider = fact("provider")
  lazy val datProvider = fact("dataProvider")
  lazy val country = fact("country")
  lazy val language = fact("language")
  lazy val rights = fact("rights")
  lazy val schemaVersions = fact("schemaVersions")

  lazy val hints = readMap("hints.txt")

  def hint(name: String): Option[String] = hints.flatMap(_.get(name))

  lazy val harvestUrl = hint("harvestUrl")
  lazy val harvestSpec = hint("harvestSpec")
  lazy val harvestPrefix = hint("harvestPrefix")
  lazy val recordCount = hint("recordCount")
  lazy val recordRootPath = hint("recordRootPath")
  lazy val uniqueElementPath = hint("uniqueElementPath")

  lazy val schemaVersionSeq: Option[Seq[String]] = schemaVersions.map(commas => commas.split(" *,").toSeq)

  def file(sipContentFileName: String): String = {
    entries.get(sipContentFileName).map { entry =>
      val inputStream = zipFile.getInputStream(entry)
      Source.fromInputStream(inputStream, "UTF-8").mkString
    } getOrElse (throw new RuntimeException(s"Unable to read file $sipContentFileName from ${file.getAbsolutePath}"))
  }

  def recDefTree(sipContentFileName: String): RecDefTree = {
    entries.get(sipContentFileName).map { entry =>
      val inputStream = zipFile.getInputStream(entry)
      val recDef = RecDef.read(inputStream)
      RecDefTree.create(recDef)
    } getOrElse (throw new RuntimeException(s"Unable to read rec def $sipContentFileName from ${file.getAbsolutePath}"))
  }

  def recMapping(sipContentFileName: String, recDefTree: RecDefTree): RecMapping = {
    entries.get(sipContentFileName).map { entry =>
      val inputStream = zipFile.getInputStream(entry)
      val mapping = RecMapping.read(inputStream, recDefTree)
      mapping.getNodeMappings.foreach { nodeMapping =>
        val inputPath = nodeMapping.inputPath.toString
        if (inputPath.startsWith("/input")) {
          nodeMapping.inputPath = Path.create(inputPath.replace("/metadata", ""))
        }
      }
      mapping
    } getOrElse (throw new RuntimeException(s"Unable to read rec def $sipContentFileName from ${file.getAbsolutePath}"))
  }

  lazy val sipMappings: Seq[SipMapping] = schemaVersionSeq.map { sequence =>
    sequence.map { schemaVersion =>
      val PrefixVersion(prefix, version) = schemaVersion
      val tree = recDefTree(s"${schemaVersion}_record-definition.xml")
      SipMapping(
        prefix = prefix,
        version = version,
        recDefTree = tree,
        validationXSD = "when you want to validate" /* file(s"${schemaVersion}_validation.xsd")*/ ,
        recMapping = recMapping(s"mapping_$prefix.xml", tree)
      )
    }
  }.getOrElse(Seq.empty[SipMapping])

  class MappingEngine(sipMapping: SipMapping) extends SipMapper {
    val groovy = new GroovyCodeResource(getClass.getClassLoader)
    val serializer = new XmlSerializer
    val namespaces = sipMapping.recDefTree.getRecDef.namespaces.map(ns => ns.prefix -> ns.uri).toMap
    val factory = new MetadataRecordFactory(namespaces)
    val runner = new MappingRunner(groovy, sipMapping.recMapping, null, false)

    override val prefix: String = sipMapping.prefix

    override def map(pocket: Pocket): Pocket = {
      val metadataRecord = factory.metadataRecordFrom(pocket.id, pocket.text, false)
      val result = new MappingResultImpl(serializer, pocket.id, runner.runMapping(metadataRecord), runner.getRecDefTree).resolve
      val xml = result.toXmlAugmented
      Pocket(pocket.id, pocket.hash, xml)
    }

  }

  def createSipMapper(prefix: String): Option[SipMapper] = sipMappings.find(_.prefix == prefix).map(new MappingEngine(_))

}