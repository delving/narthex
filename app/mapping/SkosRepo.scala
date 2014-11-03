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

import services.NarthexConfig

object SkosRepo {
  lazy val repo: SkosRepo = new SkosRepo(NarthexConfig.USER_HOME)
}

class SkosRepo(userHome: String) {

  val SUFFIX = ".xml"
  val root = new File(NarthexConfig.USER_HOME, "NarthexFiles")
  val skos = new File(root, "skos")

  def listFiles = {
    val files = if (skos.exists()) skos.listFiles.filter(file => file.getName.endsWith(SUFFIX)) else Array[File]()
    files.map(file => file.getName.substring(0, file.getName.length - SUFFIX.length)).map(_.replaceAll("_", " "))
  }

  def file(name: String) = new File(skos, name.replaceAll(" ", "_") + SUFFIX)

  def vocabulary(name: String) = SkosVocabulary(new FileInputStream(file(name)))

}
