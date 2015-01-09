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

import services.FileHandling

/**
 * @author Gerald de Jong <gerald@delving.eu>
 */

object MappedRepo {
  val SUFFIX = ".xml"
}

class MappedRepo(val home: File) {

  import dataset.MappedRepo._

  private def numberString(number: Int): String = "%05d".format(number)

  private def sourceFileName(number: Int): String = s"${numberString(number)}$SUFFIX"

  private def getFileNumber(file: File): Int = {
    val s = file.getName
    val num = s.substring(0, s.indexOf('.'))
    num.toInt
  }

  private def createXmlFile(number: Int): File = {
    home.mkdir()
    new File(home, sourceFileName(number))
  }

  def listFiles: Array[File] = {
    if (home.exists())
      home.listFiles().filter(f => f.getName.endsWith(SUFFIX)).sortBy(_.getName)
    else
      Array.empty[File]
  }

  def createFile: File = {
    val fileNumber = listFiles.lastOption.map(getFileNumber(_) + 1).getOrElse(0)
    createXmlFile(fileNumber)
  }

  def nonEmpty: Boolean = listFiles.nonEmpty

  def clear() = FileHandling.clearDir(home)
}
