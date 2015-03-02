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

package web

import java.util.concurrent.TimeUnit

import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import dataset.DatasetActor._
import dataset.DsInfo._
import mapping.SkosMappingStore.SkosMapping
import mapping.SkosVocabulary._
import mapping.VocabInfo
import mapping.VocabInfo._
import org.OrgActor.DatasetMessage
import org.OrgContext.orgContext
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.{OrgActor, OrgContext}
import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.api.mvc._
import services.ProgressReporter.ProgressState._
import services.ProgressReporter.ProgressType._
import services.Temporal._
import triplestore.GraphProperties._
import triplestore.Sparql
import triplestore.Sparql.SkosifiedField
import web.MainController.OkFile

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object AppController extends Controller with Security {

  implicit val timeout = Timeout(500, TimeUnit.MILLISECONDS)
  implicit val ts = OrgContext.TS

  def listDatasets = SecureAsync() { session => request =>
    listDsInfo.map(list => Ok(Json.toJson(list)))
  }

  def listPrefixes = Secure() { session => request =>
    val prefixes = orgContext.sipFactory.prefixRepos.map(_.prefix)
    Ok(Json.toJson(prefixes))
  }

  def datasetInfo(spec: String) = Secure() { session => request =>
    withDsInfo(spec)(dsInfo => Ok(Json.toJson(dsInfo)))
  }

  def createDataset(spec: String, character: String, mapToPrefix: String) = SecureAsync() { session => request =>
    orgContext.createDsInfo(session.actor, spec, character, mapToPrefix).map(dsInfo =>
      Ok(Json.obj("created" -> s"Dataset $spec with character $character and mapToPrefix $mapToPrefix"))
    )
  }

  def datasetProgress(spec: String) = SecureAsync() { session => request =>
    val replyData = (OrgActor.actor ? DatasetMessage(spec, CheckState, question = true)).mapTo[DatasetActorData]
    replyData.map {
      case Dormant =>
        Ok(Json.obj(
          "progressType" -> TYPE_IDLE.name
        ))
      case Active(_, progressState, progressType, count) =>
        Ok(Json.obj(
          "progressState" -> progressState.name,
          "progressType" -> progressType.name,
          "count" -> count
        ))
      case InError(message) =>
        Ok(Json.obj(
          "progressType" -> TYPE_IDLE.name,
          "errorMessage" -> message
        ))
    } recover {
      case t: AskTimeoutException =>
        Ok(Json.obj(
          "progressType" -> TYPE_IDLE.name,
          "errorMessage" -> "actor didn't answer"
        ))
    }
  }

  def command(spec: String, command: String) = SecureAsync() { session => request =>
    if (command == "interrupt") {
      OrgActor.actor ! DatasetMessage(spec, InterruptWork)
      Future(Ok("interrupt sent"))
    }
    else {
      val replyString = (OrgActor.actor ? DatasetMessage(spec, Command(command), question = true)).mapTo[String]
      replyString.map { reply =>
        Ok(Json.obj("reply" -> reply))
      } recover {
        case t: AskTimeoutException =>
          Ok(Json.obj("reply" -> "there was no reply"))
      }
    }
  }

  def uploadDataset(spec: String) = Secure(parse.multipartFormData) { session => request =>
    val datasetContext = orgContext.datasetContext(spec).mkdirs
    request.body.file("file").map { file =>
      val error = datasetContext.acceptUpload(file.filename, { target =>
        file.ref.moveTo(target, replace = true)
        Logger.info(s"Dropped file ${file.filename} on $spec: ${target.getAbsolutePath}")
        OrgActor.actor ! datasetContext.dsInfo.createMessage(ProgressTick(PREPARING, BUSY, 100))
        target
      })
      error.map {
        message => NotAcceptable(Json.obj("problem" -> message))
      } getOrElse {
        Ok
      }
    } getOrElse {
      NotAcceptable(Json.obj("problem" -> "Cannot find file in upload"))
    }
  }

  def setDatasetProperties(spec: String) = Secure(parse.json) { session => request =>
    withDsInfo(spec) { dsInfo =>
      val propertyList = (request.body \ "propertyList").as[List[String]]
      val diProps: List[NXProp] = propertyList.map(name => allProps.getOrElse(name, throw new RuntimeException(s"Property not recognized: $name")))
      val propsValueOpts = diProps.map(prop => (prop, (request.body \ "values" \ prop.name).asOpt[String]))
      val propsValues = propsValueOpts.filter(t => t._2.isDefined).map(t => (t._1, t._2.get)) // find a better way
      Logger.info(s"setDatasetProperties $propsValues")
      dsInfo.setSingularLiteralProps(propsValues: _*)
      Ok
    }
  }

  def toggleDatasetProduction(spec: String) = SecureAsync() { session => request =>
    withDsInfo(spec) { dsInfo =>
      dsInfo.toggleProduction().map(acceptanceOnly => Ok(Json.obj("acceptanceOnly" -> acceptanceOnly.toString)))
    }
  }

  // ====== stats files =====

  def index(spec: String) = Secure() { session => request =>
    OkFile(orgContext.datasetContext(spec).index)
  }

  def nodeStatus(spec: String, path: String) = Secure() { session => request =>
    orgContext.datasetContext(spec).status(path) match {
      case None => NotFound(Json.obj("path" -> path))
      case Some(file) => OkFile(file)
    }
  }

  def sample(spec: String, path: String, size: Int) = Secure() { session => request =>
    orgContext.datasetContext(spec).sample(path, size) match {
      case None => NotFound(Json.obj("path" -> path, "size" -> size))
      case Some(file) => OkFile(file)
    }
  }

  def histogram(spec: String, path: String, size: Int) = Secure() { session => request =>
    orgContext.datasetContext(spec).histogram(path, size) match {
      case None => NotFound(Json.obj("path" -> path, "size" -> size))
      case Some(file) => OkFile(file)
    }
  }

  def setRecordDelimiter(spec: String) = Secure(parse.json) { session => request =>
    val datasetContext = orgContext.datasetContext(spec)
    // todo: recordContainer instead perhaps
    val recordRoot = (request.body \ "recordRoot").as[String]
    val uniqueId = (request.body \ "uniqueId").as[String]
    datasetContext.setRawDelimiters(recordRoot, uniqueId)
    Ok
  }

  // ====== vocabularies =====

  def setSkosifiedField(spec: String) = Secure(parse.json) { session => request =>
    withDsInfo(spec) { dsInfo =>
      val histogramPathOpt = (request.body \ "histogramPath").asOpt[String]
      val skosFieldUri = (request.body \ "skosFieldUri").as[String]
      val included = (request.body \ "included").as[Boolean]
      Logger.info(s"set skos field $skosFieldUri")
      val currentSkosFields = dsInfo.getUriPropValueList(skosField)
      val action: String = if (included) {
        if (currentSkosFields.contains(skosFieldUri)) {
          "already exists"
        }
        else {
          val casesOpt = for {
            path <- histogramPathOpt
            nodeRepo <- orgContext.datasetContext(spec).nodeRepo(path)
            histogram <- nodeRepo.largestHistogram
          } yield Sparql.createCases(dsInfo, histogram)
          val futureModel = casesOpt.map { cases =>
            val q = cases.map(_.ensureSkosEntryQ).mkString
            val futureUpdate = ts.up.sparqlUpdate(q).map(ok => dsInfo.addUriProp(skosField, skosFieldUri))
            Await.result(futureUpdate, 1.minute)
          } getOrElse {
            dsInfo.addUriProp(skosField, skosFieldUri)
            val skosifiedField = SkosifiedField(dsInfo.spec, dsInfo.uri, skosFieldUri)
            OrgActor.actor ! dsInfo.createMessage(StartSkosification(skosifiedField))
          }
          "added"
        }
      }
      else {
        if (!currentSkosFields.contains(skosFieldUri)) {
          "did not exists"
        }
        else {
          // here eventual de-skosification could happen
          dsInfo.removeUriProp(skosField, skosFieldUri)
          "removed"
        }
      }
      Ok(Json.obj("action" -> action))
    }
  }

  def listVocabularies = SecureAsync() { session => request =>
    listVocabInfo.map(list => Ok(Json.toJson(list)))
  }

  def createVocabulary(spec: String) = SecureAsync() { session => request =>
    VocabInfo.createVocabInfo(session.actor, spec).map(ok =>
      Ok(Json.obj("created" -> s"Skos $spec created"))
    )
  }

  def deleteVocabulary(spec: String) = SecureAsync() { session => request =>
    VocabInfo.freshVocabInfo(spec).map { vocabInfoOpt =>
      vocabInfoOpt.map { vocabInfo =>
        vocabInfo.dropVocabulary
        Ok(Json.obj("created" -> s"Vocabulary $spec deleted"))
      } getOrElse {
        NotAcceptable(Json.obj("problem" -> s"Cannot find vocabulary $spec to delete"))
      }
    }
  }

  def uploadVocabulary(spec: String) = SecureAsync(parse.multipartFormData) { session => request =>
    withVocabInfo(spec) { vocabInfo =>
      request.body.file("file").map { bodyFile =>
        val file = bodyFile.ref.file
        ts.up.dataPutXMLFile(vocabInfo.dataUri, file).map { ok =>
          val now: String = timeToString(new DateTime())
          vocabInfo.setSingularLiteralProps(skosUploadTime -> now)
          Ok
        }
      } getOrElse {
        Future(NotAcceptable(Json.obj("problem" -> "Cannot find file in upload")))
      }
    }
  }

  def vocabularyInfo(spec: String) = Secure() { session => request =>
    withVocabInfo(spec)(vocabInfo => Ok(Json.toJson(vocabInfo)))
  }

  def vocabularyStatistics(spec: String) = SecureAsync() { session => request =>
    withVocabInfo(spec) { vocabInfo =>
      vocabInfo.conceptCount.map(stats => Ok(Json.toJson(stats)))
    }
  }

  def setVocabularyProperties(spec: String) = Secure(parse.json) { session => request =>
    withVocabInfo(spec) { vocabInfo =>
      val propertyList = (request.body \ "propertyList").as[List[String]]
      Logger.info(s"setVocabularyProperties $propertyList")
      val diProps: List[NXProp] = propertyList.map(name => allProps.getOrElse(name, throw new RuntimeException(s"Property not recognized: $name")))
      val propsValueOpts = diProps.map(prop => (prop, (request.body \ "values" \ prop.name).asOpt[String]))
      val propsValues = propsValueOpts.filter(t => t._2.isDefined).map(t => (t._1, t._2.get)) // find a better way
      vocabInfo.setSingularLiteralProps(propsValues: _*)
      Ok
    }
  }

  def getVocabularyLanguages(spec: String) = Secure() { session => request =>
    withVocabInfo(spec) { vocabInfo =>
      Ok(Json.obj("languages" -> vocabInfo.vocabulary.languages))
    }
  }

  def searchVocabulary(spec: String, sought: String, language: String) = Secure() { session => request =>
    val languageOpt = Option(language).find(lang => lang.trim.nonEmpty && lang != "-")
    Logger.info(s"Search $spec/$language: $sought")
    withVocabInfo(spec) { vocabInfo =>
      val labelSearch: LabelSearch = vocabInfo.vocabulary.search(sought, 25, languageOpt)
      Ok(Json.obj("search" -> labelSearch))
    }
  }

  def getSkosMappings(specA: String, specB: String) = SecureAsync() { session => request =>
    val store = orgContext.vocabMappingStore(specA, specB)
    store.getMappings.map(tuples => Ok(Json.toJson(tuples.map(t => List(t._1, t._2)))))
  }

  def toggleSkosMapping(specA: String, specB: String) = SecureAsync(parse.json) { session => request =>
    val uriA = (request.body \ "uriA").as[String]
    val uriB = (request.body \ "uriB").as[String]
    val store = orgContext.vocabMappingStore(specA, specB)
    store.toggleMapping(SkosMapping(session.actor, uriA, uriB)).map { action =>
      Ok(Json.obj("action" -> action))
    }
  }

  def getTermVocabulary(spec: String) = Secure() { session => request =>
    withDsInfo(spec) { dsInfo =>
      val results = dsInfo.vocabulary.concepts.map(concept => {
        //          val freq: Int = concept.frequency.getOrElse(0)
        val label = concept.getAltLabel(None).map(_.text).getOrElse("Label missing")
        Json.obj(
          "uri" -> concept.resource.toString,
          "label" -> label,
          "frequency" -> concept.frequency
        )
      })
      Ok(Json.toJson(results))
    }
  }

  def getTermMappings(dsSpec: String) = SecureAsync() { session => request =>
    val store = orgContext.termMappingStore(dsSpec)
    store.getMappings(categories = false).map(entries => Ok(Json.toJson(entries)))
  }

  def getCategoryMappings(dsSpec: String) = SecureAsync() { session => request =>
    val store = orgContext.termMappingStore(dsSpec)
    store.getMappings(categories = true).map(entries => Ok(Json.toJson(entries)))
  }

  def toggleTermMapping(dsSpec: String, vocabSpec: String) = SecureAsync(parse.json) { session => request =>
    val uriA = (request.body \ "uriA").as[String]
    val uriB = (request.body \ "uriB").as[String]
    val store = orgContext.termMappingStore(dsSpec)
    withVocabInfo(vocabSpec) { vocabInfo =>
      store.toggleMapping(SkosMapping(session.actor, uriA, uriB), vocabInfo).map { action =>
        Ok(Json.obj("action" -> action))
      }
    }
  }

  def listSipFiles(spec: String) = Secure() { session => request =>
    val datasetContext = orgContext.datasetContext(spec)
    val fileNames = datasetContext.sipRepo.listSips.map(_.file.getName)
    Ok(Json.obj("list" -> fileNames))
  }

  def deleteLatestSipFile(spec: String) = Secure() { session => request =>
    val datasetContext = orgContext.datasetContext(spec)
    val sips = datasetContext.sipRepo.listSips
    if (sips.size < 2) {
      NotFound(Json.obj("problem" -> s"Refusing to delete the last SIP file $spec"))
    }
    else {
      FileUtils.deleteQuietly(sips.head.file)
      Ok(Json.obj("deleted" -> sips.head.file.getName))
    }
  }

  def getCategoryList = SecureAsync() { session => request =>
    val map: Future[Option[VocabInfo]] = listVocabInfo.map(list => list.find(_.spec == CATEGORIES_SPEC))
    map.map { catVocabInfoOpt =>
      catVocabInfoOpt.map { catVocabInfo =>
        val count = Await.result(catVocabInfo.conceptCount, 30.seconds)
        if (count > 5 && count < 30) {
          val categories = catVocabInfo.vocabulary.concepts.sortBy(_.prefLabels.head.text).map { c =>
            val details = c.altLabels.headOption.map(_.text).getOrElse("???")
            Json.obj(
              "uri" -> c.resource.toString,
              "code" -> c.prefLabels.head.text,
              "details" -> details
            )
          }
          Ok(Json.obj("categories" -> categories))
        }
        else {
          Ok(Json.obj("noCategories" -> s"Concept count not within range (5 - 30): $count"))
        }
      } getOrElse {
        Ok(Json.obj("noCategories" -> s"No SKOS vocabulary named '$CATEGORIES_SPEC'"))
      }
    }
  }

  def gatherCategoryCounts = Secure() { session => request =>
    orgContext.startCategoryCounts()
    Ok
  }

  def listSheets = Secure() { session => request =>
    Ok(Json.obj("sheets" -> orgContext.categoriesRepo.listSheets))
  }

  def sheet(spec: String) = Action(parse.anyContent) { implicit request =>
    OkFile(orgContext.categoriesRepo.sheet(spec))
  }

}
