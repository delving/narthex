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

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object DatasetInfo {

  case class PropType(uriOpt: Option[String])

  val stringProp = PropType(None)
  val timeProp = PropType(None)
  val intProp = PropType(None)
  val booleanProp = PropType(None)

  case class NXProp(name: String, dataType: PropType = stringProp) {
    val uri = s"${NX_NAMESPACE}Dataset-Info-Attributes#$name"
  }

  var datasetName = NXProp("datasetName")
  var datasetPrefix = NXProp("datasetPrefix")
  var datasetAdministrator = NXProp("datasetAdministrator")
  var datasetProvider = NXProp("datasetProvider")
  var datasetLanguage = NXProp("datasetLanguage")
  var datasetRights = NXProp("datasetRights")
  var datasetNotes = NXProp("datasetNotes")
  var datasetRecordCount = NXProp("datasetRecordCount", intProp)

  var stateRaw = NXProp("stateRaw", timeProp)
  var stateRawAnalyzed = NXProp("stateRawAnalyzed", timeProp)
  var stateSource = NXProp("stateSource", timeProp)
  var stateMappable = NXProp("stateMappable", timeProp)
  var stateProcessable = NXProp("stateProcessable", timeProp)
  var stateProcessed = NXProp("stateProcessed", timeProp)
  var stateAnalyzed = NXProp("stateAnalyzed", timeProp)
  var stateSaved = NXProp("stateSaved", timeProp)

  var harvestType = NXProp("harvestType")
  var harvestURL = NXProp("harvestURL")
  var harvestDataset = NXProp("harvestDataset")
  var harvestPrefix = NXProp("harvestPrefix")
  var harvestPreviousTime = NXProp("harvestPreviousTime", timeProp)
  var harvestDelay = NXProp("harvestDelay")
  var harvestDelayUnit = NXProp("harvestDelayUnit")

  var processedValid = NXProp("processedValid", intProp)
  var processedInvalid = NXProp("processedInvalid", intProp)

  var publishOAIPMH = NXProp("publishOAIPMH", booleanProp)
  var publishIndex = NXProp("publishIndex", booleanProp)
  var publishLOD = NXProp("publishLOD", booleanProp)
  var categoriesInclude = NXProp("categoriesInclude", booleanProp)

}

class DatasetInfo(name: String, client: TripleStoreClient) {

  import dataset.DatasetInfo._

  val DATASET_URI = s"$NX_URI_PREFIX/dataset/${urlEncodeValue(name)}"

  val futureModel = client.dataGet(DATASET_URI).fallbackTo {
    val m = ModelFactory.createDefaultModel()
    val uri = m.getResource(DATASET_URI)
    val propUri = m.getProperty(datasetName.uri)
    m.add(uri, propUri, m.createLiteral(name))
    client.dataPost(DATASET_URI, m).map(ok => m)
  }

  def getProp(prop: NXProp): Future[Option[String]] = futureModel.map { m =>
    val uri = m.getResource(DATASET_URI)
    val propUri = m.getProperty(prop.uri)
    val objects = m.listObjectsOfProperty(uri, propUri)
    if (objects.hasNext) Some(objects.next().asLiteral().getString) else None
  }

  def setProp(prop: NXProp, value: String): Future[Model] = futureModel.flatMap { m =>
    val uri = m.getResource(DATASET_URI)
    val propUri = m.getProperty(prop.uri)
    val sparql =
      s"""
         |WITH <$uri>
         |DELETE { <$uri> <$propUri> ?o }
         |INSERT { <$uri> <$propUri> "$value" }
         |WHERE { OPTIONAL { <$uri> <$propUri> ?o } }
       """.stripMargin
    client.update(sparql).map { ok =>
      m.removeAll(uri, propUri, null)
      m.add(uri, propUri, m.createLiteral(value))
      m
    }
  }

  def removeProp(prop: NXProp) = futureModel.flatMap { m =>
    val uri = m.getResource(DATASET_URI)
    val propUri = m.getProperty(prop.uri)
    val sparql =
      s"""
         |WITH <$uri>
         |DELETE { <$uri> <$propUri> ?o }
         |WHERE { <$uri> <$propUri> ?o }
       """.stripMargin
    client.update(sparql).map{ok =>
      m.removeAll(uri, propUri, null)
      m
    }
  }
}
