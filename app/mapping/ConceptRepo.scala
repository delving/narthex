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
package mapping

import java.io.{File, FileInputStream}

import org.apache.commons.io.input.BOMInputStream
import play.api.Logger

class ConceptRepo(home: File) {

  lazy val conceptSchemes: Seq[ConceptScheme] = {
    Logger.info("Reading concept schemes")
    val schemes = home.listFiles.filter(_.getName.endsWith(".xml")).flatMap { file =>
      Logger.info(s"Reading $file")
      ConceptScheme.read(new BOMInputStream(new FileInputStream(file)))
    }
    Logger.info(s"Concept Schemes: ${schemes.mkString(",")}")
    schemes
  }

}
