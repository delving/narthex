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
import org.apache.jena.riot.{RDFDataMgr, RDFFormat}
import services.FileHandling

import scala.collection.JavaConversions._

/**
 * @author Gerald de Jong <gerald@delving.eu>
 */

object ProcessedRepo {
  val SUFFIX = ".xml"

  case class GraphChunk(dataset: Dataset) {

    private def sparqlUpdateGraph(dataset: Dataset, graphUri: String) = {
      val model = dataset.getNamedModel(graphUri)
      val triples = new StringWriter()
      RDFDataMgr.write(triples, model, RDFFormat.NTRIPLES_UTF8)
      s"""
        |DROP SILENT GRAPH <$graphUri>;
        |INSERT DATA { GRAPH <$graphUri> {
        |$triples}};
       """.stripMargin.trim
    }

    def toSparqlUpdate: String = dataset.listNames().toList.map(g => sparqlUpdateGraph(dataset, g)).mkString("\n")
  }
  
  trait GraphReader {
    def readChunk: Option[GraphChunk]
    def close(): Unit
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

  def createGraphReader(chunkSize: Int) = new GraphReader {
    val reader = FileHandling.reader(baseFile)
    var reading = true
    val LineId = "<!--<([^>]*)>-->".r

    def readChunk: Option[GraphChunk] = {
      val dataset = DatasetFactory.createMem()
      val recordText = new StringBuilder
      var graphCount = 0
      while (reading) {
        Option(reader.readLine()).map {
          case LineId(graphName) =>
            dataset.getNamedModel(graphName).read(new StringReader(recordText.toString()), null, "RDF/XML")
            graphCount += 1
            recordText.clear()
            if (graphCount >= chunkSize) reading = false
          case x: String =>
            recordText.append(x).append("\n")
        } getOrElse {
          reader.close()
          reading = false
        }
      }
      if (graphCount > 0) Some(GraphChunk(dataset)) else None
    }

    override def close(): Unit = {
      reader.close()
    }
  }
  
  
  
//
//  class DatasetIteratee(bucketSize: Int) extends Iteratee[String, Dataset] {
//    override def fold[B](folder: (Step[String, Dataset]) => Future[B])(implicit ec: ExecutionContext): Future[B] = {
//
//    }
//  }
}
