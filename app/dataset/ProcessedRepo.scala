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

import com.hp.hpl.jena.query.{Dataset, DatasetFactory}
import services.FileHandling

/**
 * @author Gerald de Jong <gerald@delving.eu>
 */

object ProcessedRepo {
  val SUFFIX = ".xml"

  trait DatasetReader {
    def nextDataset: Option[Dataset]
  }

}

class ProcessedRepo(val home: File) {

  import dataset.ProcessedRepo._

  home.mkdir()

  private def numberString(number: Int): String = "%05d".format(number)

  private def sourceFileName(number: Int): String = s"${numberString(number)}$SUFFIX"

  private def getFileNumber(file: File): Int = file.getName.substring(0, file.getName.indexOf('.')).toInt

  private def xmlFile(number: Int): File = new File(home, sourceFileName(number))

  // ===================

  val baseFile = xmlFile(0)

  def nonEmpty: Boolean = baseFile.exists()

  def listFiles: Array[File] = home.listFiles().filter(f => f.getName.endsWith(SUFFIX)).sortBy(_.getName)

  def createFile: File = {
    val fileNumber = listFiles.lastOption.map(getFileNumber(_) + 1).getOrElse(0)
    xmlFile(fileNumber)
  }

  def clear() = FileHandling.clearDir(home)

  def createDatasetReader(maxGraphs: Int) = new DatasetReader {
    val reader = FileHandling.reader(baseFile)
    lazy val LineId = "<!--<([^>]*)>-->".r

    override def nextDataset: Option[Dataset] = {
      val dataset = DatasetFactory.createMem()
      val recordText = new StringBuilder
      var reading = true
      var graphCount = 0
      while (reading) {
        Option(reader.readLine()).map {
          case LineId(graphName) =>
            dataset.getNamedModel(graphName).read(new StringReader(recordText.toString()), null, "RDF/XML")
            graphCount += 1
            recordText.clear()
            if (graphCount >= maxGraphs) reading = false
          case x: String =>
            recordText.append(x).append("\n")
        } getOrElse {
          reader.close()
          reading = false
        }
      }
      if (graphCount > 0) Some(dataset) else None
    }
  }
}
