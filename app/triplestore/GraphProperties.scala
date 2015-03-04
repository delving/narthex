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

package triplestore

import com.hp.hpl.jena.rdf.model.{Model, Property}

object GraphProperties {

  val NX_NAMESPACE = "http://schemas.delving.eu/narthex/terms/"

  val XML = "http://www.w3.org/XML/1998/namespace"
  val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
  val DC = "http://purl.org/dc/elements/1.1/"
  val SKOS = "http://www.w3.org/2004/02/skos/core#"

  case class PropType(uriOpt: Option[String])

  val stringProp = PropType(None)
  val timeProp = PropType(None)
  val intProp = PropType(None)
  val booleanProp = PropType(None)
  val uriProp = PropType(None)

  var allProps = Map.empty[String, NXProp]

  case class NXProp(name: String, dataType: PropType = stringProp) {
    val uri = s"$NX_NAMESPACE$name"
    allProps = allProps + (name -> this)
    override def toString = uri
  }

  val username = NXProp("username")
  val passwordHash = NXProp("passwordHash")
  val userEMail = NXProp("userEMail")
  val userFirstName = NXProp("userFirstName")
  val userLastName = NXProp("userLastName")
  val actorOwner = NXProp("actorOwner")

  val belongsTo = NXProp("belongsTo")
  val synced = NXProp("synced")
  val deleted = NXProp("deleted")
  val acceptanceOnly = NXProp("acceptanceOnly")

  val datasetCharacter = NXProp("datasetCharacter")

  val datasetSpec = NXProp("datasetSpec")
  val datasetName = NXProp("datasetName")
  val datasetDescription = NXProp("datasetDescription")
  val datasetOwner = NXProp("datasetOwner")
  val datasetAggregator = NXProp("datasetAggregator")

  val datasetLanguage = NXProp("datasetLanguage")
  val datasetRights = NXProp("datasetRights")

  val datasetMapToPrefix = NXProp("datasetMapToPrefix")

  val datasetRecordCount = NXProp("datasetRecordCount", intProp)
  val datasetErrorTime = NXProp("datasetErrorTime")
  val datasetErrorMessage = NXProp("datasetErrorMessage")

  val skosField = NXProp("skosField", uriProp)

  val stateRaw = NXProp("stateRaw", timeProp)
  val stateRawAnalyzed = NXProp("stateRawAnalyzed", timeProp)
  val stateSourced = NXProp("stateSourced", timeProp)
  val stateMappable = NXProp("stateMappable", timeProp)
  val stateProcessable = NXProp("stateProcessable", timeProp)
  val stateProcessed = NXProp("stateProcessed", timeProp)
  val stateAnalyzed = NXProp("stateAnalyzed", timeProp)
  val stateSaved = NXProp("stateSaved", timeProp)

  val harvestType = NXProp("harvestType")
  val harvestURL = NXProp("harvestURL")
  val harvestDataset = NXProp("harvestDataset")
  val harvestPrefix = NXProp("harvestPrefix")
  val harvestSearch = NXProp("harvestSearch")
  val harvestPreviousTime = NXProp("harvestPreviousTime", timeProp)
  val harvestDelay = NXProp("harvestDelay")
  val harvestDelayUnit = NXProp("harvestDelayUnit")
  val harvestIncremental = NXProp("harvestIncremental")

  val idFilterType = NXProp("idFilterType")
  val idFilterExpression = NXProp("idFilterExpression")

  val processedValid = NXProp("processedValid", intProp)
  val processedInvalid = NXProp("processedInvalid", intProp)
  val processedIncrementalValid = NXProp("processedIncrementalValid", intProp)
  val processedIncrementalInvalid = NXProp("processedIncrementalInvalid", intProp)

  val publishOAIPMH = NXProp("publishOAIPMH", booleanProp)
  val publishIndex = NXProp("publishIndex", booleanProp)
  val publishLOD = NXProp("publishLOD", booleanProp)
  val categoriesInclude = NXProp("categoriesInclude", booleanProp)

  val skosSpec = NXProp("skosSpec")
  val skosName = NXProp("skosName")
  val skosOwner = NXProp("skosOwner", uriProp)
  val skosUploadTime = NXProp("skosUploadTime", timeProp)

  val exactMatch = s"${SKOS}exactMatch"
  val belongsToCategory = NXProp("belongsToCategory", uriProp)
  val mappingConcept = NXProp("mappingConcept", uriProp)
  val mappingVocabulary = NXProp("mappingVocabulary", uriProp)
  val mappingDeleted = NXProp("mappingDeleted", booleanProp)
  val mappingTime = NXProp("mappingTime", timeProp)

  val skosFrequency = s"${NX_NAMESPACE}skosFrequency"

  val rdfType = s"${RDF}type"
  val datasetEntity = s"${NX_NAMESPACE}Dataset"
  val recordEntity = s"${NX_NAMESPACE}Record"
  val mappingEntity = s"${NX_NAMESPACE}Mapping"
  val actorEntity = s"${NX_NAMESPACE}Actor"
  val skosCollection = s"${SKOS}Collection"
  val actorsGraph = s"${NX_NAMESPACE}Actors"


  def nxProp(m: Model, localName: String): Property = {
    m.setNsPrefix("nx", NX_NAMESPACE)
    m.getProperty(NX_NAMESPACE, localName)
  }


}
