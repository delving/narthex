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

import eu.delving.metadata.{RecDef, RecDefTree, RecMapping}
import services.SipRepo.SipFile

import scala.collection.JavaConversions._
import scala.io.Source

/**
 * @author Gerald de Jong <gerald@delving.eu>
 */

object SipRepo {


  case class SipMapping(prefix: String, version: String, recDefTree: RecDefTree, validationXSD: String, recMapping: RecMapping)

  val PrefixVersion = "(.*)_(.*)".r

  case class SipFile(file: File) {

    lazy val zipFile = new ZipFile(file)

    lazy val entries = zipFile.entries().map(entry => entry.getName -> entry).toMap

    def readMap(fileName: String): Option[Map[String, String]] = {
      entries.get(fileName).map { entry =>
        val inputStream = zipFile.getInputStream(entry)
        val lines = Source.fromInputStream(inputStream, "UTF-8").getLines()
        lines.flatMap { line =>
          val equals = line.indexOf("=")
          if (equals < 0) None else Some(line.substring(0, equals).trim -> line.substring(equals + 1).trim)
        }.toMap
      }
    }

    lazy val facts = readMap("dataset_facts.txt")

    def fact(name: String): Option[String] = facts.flatMap(_.get(name)).find(_.nonEmpty)

    lazy val spec = fact("spec")
    lazy val orgId = fact("orgId")
    lazy val provider = fact("provider")
    lazy val datProvider = fact("dataProvider")
    lazy val country = fact("country")
    lazy val language = fact("language")
    lazy val rights = fact("rights")
    lazy val schemaVersions = fact("schemaVersions")

    lazy val hints = readMap("hints.txt")

    def hint(name: String): Option[String] = hints.flatMap(_.get(name)).find(_.nonEmpty)

    lazy val harvestUrl = hint("harvestUrl")
    lazy val harvestSpec = hint("harvestSpec")
    lazy val harvestPrefix = hint("harvestPrefix")
    lazy val recordCount = hint("recordCount")
    lazy val recordRootPath = hint("recordRootPath")
    lazy val uniqueElementPath = hint("uniqueElementPath")

    lazy val schemaVersionSeq: Option[Seq[String]] = schemaVersions.map(commas => commas.split(" *,").toSeq)

    def file(fileName: String): String = {
      entries.get(fileName).map { entry =>
        val inputStream = zipFile.getInputStream(entry)
        Source.fromInputStream(inputStream, "UTF-8").mkString
      } getOrElse (throw new RuntimeException(s"Unable to read file $fileName from ${file.getAbsolutePath}"))
    }

    def recDefTree(fileName: String): RecDefTree = {
      entries.get(fileName).map { entry =>
        val inputStream = zipFile.getInputStream(entry)
        val recDef = RecDef.read(inputStream)
        RecDefTree.create(recDef)
      } getOrElse (throw new RuntimeException(s"Unable to read rec def $fileName from ${file.getAbsolutePath}"))
    }

    def recMapping(fileName: String, recDefTree: RecDefTree): RecMapping = {
      entries.get(fileName).map { entry =>
        val inputStream = zipFile.getInputStream(entry)
        RecMapping.read(inputStream, recDefTree)
      } getOrElse (throw new RuntimeException(s"Unable to read rec def $fileName from ${file.getAbsolutePath}"))
    }

    lazy val sipMappings: Map[String, SipMapping] = schemaVersionSeq.map { sequence =>
      sequence.map { schemaVersion =>
        val PrefixVersion(prefix, version) = schemaVersion
        val tree = recDefTree(s"${schemaVersion}_record-definition.xml")
        prefix -> SipMapping(
          prefix = prefix,
          version = version,
          recDefTree = tree,
          validationXSD = "when you want to validate"/* file(s"${schemaVersion}_validation.xsd")*/,
          recMapping = recMapping(s"mapping_$prefix.xml", tree)
        )
      }.toMap
    }.getOrElse(Map.empty[String, SipMapping])

  }

}

class SipRepo(home: File) {
  home.mkdir()

  def latestSIPFile: Option[SipFile] = {
    val zipFIles = home.listFiles().filter(_.getName.endsWith("zip"))
    zipFIles.sortBy(_.getName).lastOption.map(SipFile)
  }

}
