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

import org.apache.commons.io.IOUtils

/**
 * @author Gerald de Jong <gerald@delving.eu>
 */

object SipFactory {

  val RECORD_DEFINITION_SUFFIX = "_record-definition.xml"
  val VALIDATION_SUFFIX = "_validation.xsd"

  case class SipGenerationFacts
  (spec: String,
   name: String,
   dataProvider: String,
   language: String,
   rights: String)

  /*
    todo: remaining

    country=Netherlands
    dataProviderUri=http://id.musip.nl/crm_e39/7426
    orgId=dimcon
    provider=Rijksdienst voor het Cultureel Erfgoed
    type=IMAGE
   */

  def apply(home: File) = new SipFactory(home)

}

class SipFactory(home: File) {

  lazy val prefixes = home.listFiles().filter(_.isDirectory).map(new SipPrefixRepo(_))

}

class SipPrefixRepo(home: File) {
  import dataset.SipFactory._
  val prefix = home.getName

  lazy val recordDefinition = home.listFiles().find(f => f.getName.endsWith(RECORD_DEFINITION_SUFFIX)).getOrElse(
    throw new RuntimeException(s"Missing record definition in $home")
  )

  lazy val validation = home.listFiles().find(f => f.getName.endsWith(VALIDATION_SUFFIX)).getOrElse(
    throw new RuntimeException(s"Missing validation xsd in $home")
  )

  lazy val schemaVersions = recordDefinition.getName.substring(0, recordDefinition.getName.length - RECORD_DEFINITION_SUFFIX.length)

  def generateSip(sipFile: File, sourceXmlFile: File, facts: SipGenerationFacts) = {
    val zos = new ZipOutputStream(new FileOutputStream(sipFile))

    // facts
    zos.putNextEntry(new ZipEntry("narthex_facts.txt"))
    val factsString =
      s"""spec=${facts.spec}
         |name=${facts.name}
         |dataProvider=${facts.dataProvider}
         |language=${facts.language}
         |schemaVersions=$schemaVersions
         |rights=${facts.rights}
         |""".stripMargin
    zos.write(factsString.getBytes("UTF-8"))
    zos.closeEntry()

    // record definition and validation
    def copyIn(file: File) = {
      zos.putNextEntry(new ZipEntry(file.getName))
      val fileIn = new FileInputStream(sourceXmlFile)
      IOUtils.copy(fileIn, zos)
      fileIn.close()
      zos.closeEntry()
    }
    copyIn(recordDefinition)
    copyIn(validation)

    // source, gzipped
    zos.putNextEntry(new ZipEntry(SipRepo.SOURCE_FILE))
    val gzipOut = new GZIPOutputStream(zos)
    val sourceIn = new FileInputStream(sourceXmlFile)
    IOUtils.copy(sourceIn, gzipOut)
    sourceIn.close()
    gzipOut.close()

    zos.close()
  }
}
