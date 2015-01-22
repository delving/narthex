//===========================================================================
//    Copyright 2015 Delving B.V.
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

import com.hp.hpl.jena.rdf.model.{Model, ModelFactory}
import services.NarthexConfig._
import services.StringHandling.urlEncodeValue
import triplestore.TripleStoreClient
import triplestore.TripleStoreClient._

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._

object DatasetInfo {

  case class DIProp(name: String, dataType: PropType = stringProp) {
    val uri = s"$NX_NAMESPACE$name"
  }

  var datasetName = DIProp("datasetName")
  var datasetMapTo = DIProp("datasetMapTo")
  var datasetAdministrator = DIProp("datasetAdministrator")
  var datasetProvider = DIProp("datasetProvider")
  var datasetLanguage = DIProp("datasetLanguage")
  var datasetRights = DIProp("datasetRights")
  var datasetNotes = DIProp("datasetNotes")
  var datasetRecordCount = DIProp("datasetRecordCount", intProp)
  var skosField = DIProp("skosField", uriProp)

  var stateRaw = DIProp("stateRaw", timeProp)
  var stateRawAnalyzed = DIProp("stateRawAnalyzed", timeProp)
  var stateSource = DIProp("stateSource", timeProp)
  var stateMappable = DIProp("stateMappable", timeProp)
  var stateProcessable = DIProp("stateProcessable", timeProp)
  var stateProcessed = DIProp("stateProcessed", timeProp)
  var stateAnalyzed = DIProp("stateAnalyzed", timeProp)
  var stateSaved = DIProp("stateSaved", timeProp)

  var harvestType = DIProp("harvestType")
  var harvestURL = DIProp("harvestURL")
  var harvestDataset = DIProp("harvestDataset")
  var harvestPrefix = DIProp("harvestPrefix")
  var harvestPreviousTime = DIProp("harvestPreviousTime", timeProp)
  var harvestDelay = DIProp("harvestDelay")
  var harvestDelayUnit = DIProp("harvestDelayUnit")

  var processedValid = DIProp("processedValid", intProp)
  var processedInvalid = DIProp("processedInvalid", intProp)

  var publishOAIPMH = DIProp("publishOAIPMH", booleanProp)
  var publishIndex = DIProp("publishIndex", booleanProp)
  var publishLOD = DIProp("publishLOD", booleanProp)
  var categoriesInclude = DIProp("categoriesInclude", booleanProp)

}

class DatasetInfo(name: String, client: TripleStoreClient) {

  import dataset.DatasetInfo._

  val datasetUri = s"$NX_URI_PREFIX/dataset/${urlEncodeValue(name)}"

  val futureModel = client.dataGet(datasetUri).fallbackTo {
    val m = ModelFactory.createDefaultModel()
    val uri = m.getResource(datasetUri)
    val propUri = m.getProperty(datasetName.uri)
    m.add(uri, propUri, m.createLiteral(name))
    client.dataPost(datasetUri, m).map(ok => m)
  }

  val m = Await.result(futureModel, 20 seconds)

  def getLiteralProp(prop: DIProp): Option[String] = {
    val uri = m.getResource(datasetUri)
    val propUri = m.getProperty(prop.uri)
    val objects = m.listObjectsOfProperty(uri, propUri)
    if (objects.hasNext) Some(objects.next().asLiteral().getString) else None
  }
  
  def setLiteralProps(tuples: (DIProp, String)*): Future[Model] = {
    val uri = m.getResource(datasetUri)
    val propVal = tuples.map(t => (m.getProperty(t._1.uri), t._2))
    val sparqlPerProp = propVal.map { pv =>
      val propUri = pv._1
      s"""
         |WITH <$uri>
         |DELETE { 
         |   <$uri> <$propUri> ?o 
         |}
         |INSERT { 
         |   <$uri> <$propUri> "${pv._2}" 
         |}
         |WHERE { 
         |   OPTIONAL {
         |      <$uri> <$propUri> ?o
         |   } 
         |}
       """.stripMargin.trim
    }
    val sparql = sparqlPerProp.mkString(";\n")
    client.update(sparql).map { ok =>
      propVal.foreach { pv =>
        m.removeAll(uri, pv._1, null)
        m.add(uri, pv._1, m.createLiteral(pv._2))
      }
      m
    }
  }

  def removeLiteralProp(prop: DIProp): Future[Model] = {
    val uri = m.getResource(datasetUri)
    val propUri = m.getProperty(prop.uri)
    val sparql =
      s"""
         |WITH <$uri>
         |DELETE {
         |   <$uri> <$propUri> ?o
         |}
         |WHERE {
         |   <$uri> <$propUri> ?o
         |}
       """.stripMargin
    client.update(sparql).map { ok =>
      m.removeAll(uri, propUri, null)
      m
    }
  }

  def getUriProps(prop: DIProp): List[String] = {
    val uri = m.getResource(datasetUri)
    val propUri = m.getProperty(prop.uri)
    m.listObjectsOfProperty(uri, propUri).map(node => node.asResource().toString).toList
  }

  def setUriProp(prop: DIProp, uriValue: String): Future[Model] = {
    val uri = m.getResource(datasetUri)
    val propUri = m.getProperty(prop.uri)
    val uriValueUri = m.getResource(uriValue)
    val sparql =s"""
         |INSERT DATA {
         |   GRAPH <$uri> {
         |      <$uri> <$propUri> <$uriValueUri>
         |   }
         |}
       """.stripMargin.trim
    client.update(sparql).map { ok =>
      m.add(uri, propUri, uriValueUri)
      m
    }
  }

  def removeIUriProp(prop: DIProp, uriValue: String): Future[Model] = futureModel.flatMap { m =>
    val uri = m.getResource(datasetUri)
    val propUri = m.getProperty(prop.uri)
    val uriValueUri = m.getProperty(uriValue)
    val sparql =
      s"""
         |DELETE DATA FROM <$uri> {
         |   <$uri> <$propUri> <$uriValueUri>
         |}
       """.stripMargin
    client.update(sparql).map { ok =>
      m.remove(uri, propUri, uriValueUri)
      m
    }
  }
}
