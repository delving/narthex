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

  case class SipGenerationFacts
  (spec: String,
   dataProvider: String)

  /*
  country=Netherlands
  dataProvider=Van Abbemuseum
  dataProviderUri=http://id.musip.nl/crm_e39/7426
  language=nl
  name=Van Abbemuseum
  orgId=dimcon
  provider=Rijksdienst voor het Cultureel Erfgoed
  rights=http://creativecommons.org/licenses/by/3.0/
  schemaVersions=icn_1.0.3
  spec=van-abbe-museum
  type=IMAGE
   */

  def apply(home: File) = new SipFactory(home)

}

class SipFactory(home: File) {

  lazy val prefixes = home.listFiles().filter(_.isDirectory).map(new SipPrefixRepo(_))

}

class SipPrefixRepo(home: File) {

  val prefix = home.getName

  lazy val recordDefinition = home.listFiles().find(f => f.getName.endsWith("_record-definition.xml")).getOrElse(
    throw new RuntimeException(s"Missing record definition in $home")
  )

  lazy val validation = home.listFiles().find(f => f.getName.endsWith("_validation.xsd")).getOrElse(
    throw new RuntimeException(s"Missing validation xsd in $home")
  )

  def generateSip(sipFile: File, sourceXmlFile: File, facts: Map[String, String]) = {
    val zos = new ZipOutputStream(new FileOutputStream(sipFile))

    zos.putNextEntry(new ZipEntry("narthex_facts.txt"))
    zos.closeEntry()

    def copyIn(file: File) = {
      zos.putNextEntry(new ZipEntry(file.getName))
      val fileIn = new FileInputStream(sourceXmlFile)
      IOUtils.copy(fileIn, zos)
      fileIn.close()
      zos.closeEntry()
    }
    copyIn(recordDefinition)
    copyIn(validation)

    zos.putNextEntry(new ZipEntry(SipRepo.SOURCE_FILE))
    val gzipOut = new GZIPOutputStream(zos)
    val sourceIn = new FileInputStream(sourceXmlFile)
    IOUtils.copy(sourceIn, gzipOut)
    sourceIn.close()
    gzipOut.close()

    zos.close()
  }
}
