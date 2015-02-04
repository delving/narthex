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
import mapping.SkosVocabulary._
import org.OrgContext.NX_NAMESPACE

object GraphProperties {

  case class PropType(uriOpt: Option[String])

  val stringProp = PropType(None)
  val timeProp = PropType(None)
  val intProp = PropType(None)
  val booleanProp = PropType(None)
  val uriProp = PropType(None)

  case class NXProp(name: String, dataType: PropType = stringProp) {
    val uri = s"$NX_NAMESPACE$name"
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

  var allDatasetProps = Map.empty[String, DIProp]

  case class DIProp(name: String, dataType: PropType = stringProp) {
    val uri = s"$NX_NAMESPACE$name"
    allDatasetProps = allDatasetProps + (name -> this)
    override def toString = uri
  }

  val datasetCharacter = DIProp("datasetCharacter")

  val datasetSpec = DIProp("datasetSpec")
  val datasetName = DIProp("datasetName")
  val datasetDescription = DIProp("datasetDescription")
  val datasetOwner = DIProp("datasetOwner")
  val datasetLanguage = DIProp("datasetLanguage")
  val datasetRights = DIProp("datasetRights")

  val datasetMapToPrefix = DIProp("datasetMapToPrefix")

  val datasetRecordCount = DIProp("datasetRecordCount", intProp)
  val datasetErrorTime = DIProp("datasetErrorTime")
  val datasetErrorMessage = DIProp("datasetErrorMessage")

  val skosField = DIProp("skosField", uriProp)

  val stateRaw = DIProp("stateRaw", timeProp)
  val stateRawAnalyzed = DIProp("stateRawAnalyzed", timeProp)
  val stateSource = DIProp("stateSource", timeProp)
  val stateMappable = DIProp("stateMappable", timeProp)
  val stateProcessable = DIProp("stateProcessable", timeProp)
  val stateProcessed = DIProp("stateProcessed", timeProp)
  val stateAnalyzed = DIProp("stateAnalyzed", timeProp)
  val stateSaved = DIProp("stateSaved", timeProp)

  val harvestType = DIProp("harvestType")
  val harvestURL = DIProp("harvestURL")
  val harvestDataset = DIProp("harvestDataset")
  val harvestPrefix = DIProp("harvestPrefix")
  val harvestSearch = DIProp("harvestSearch")
  val harvestPreviousTime = DIProp("harvestPreviousTime", timeProp)
  val harvestDelay = DIProp("harvestDelay")
  val harvestDelayUnit = DIProp("harvestDelayUnit")

  val processedValid = DIProp("processedValid", intProp)
  val processedInvalid = DIProp("processedInvalid", intProp)

  val publishOAIPMH = DIProp("publishOAIPMH", booleanProp)
  val publishIndex = DIProp("publishIndex", booleanProp)
  val publishLOD = DIProp("publishLOD", booleanProp)
  val categoriesInclude = DIProp("categoriesInclude", booleanProp)


  var allSkosProps = Map.empty[String, SIProp]

  case class SIProp(name: String, dataType: PropType = stringProp) {
    val uri = s"$NX_NAMESPACE$name"
    allSkosProps = allSkosProps + (name -> this)
  }

  val skosSpec = SIProp("skosSpec")
  val skosName = SIProp("skosName")
  val skosOwner = SIProp("skosOwner", uriProp)
  val skosUploadTime = SIProp("skosUploadTime", timeProp)

  case class MAProp(name: String, dataType: PropType = stringProp) {
    val uri = s"$NX_NAMESPACE$name"
    override def toString = uri
  }

  val exactMatch = s"${SKOS}exactMatch"
  val mappingConcept = MAProp("mappingConcept", uriProp)
  val mappingVocabulary = MAProp("mappingVocabulary", uriProp)
  val mappingDeleted = MAProp("mappingDeleted", booleanProp)
  val mappingTime = MAProp("mappingTime", timeProp)

  def nxProp(m: Model, localName: String): Property = {
    m.setNsPrefix("nx", NX_NAMESPACE)
    m.getProperty(NX_NAMESPACE, localName)
  }
}
