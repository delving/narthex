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

package controllers

import controllers.OaiPmh.Service.{QueryKey, Verb}
import org.joda.time.DateTime
import play.api.Logger
import play.api.mvc._
import services.NarthexConfig._
import services.{BaseXTools, NarthexConfig, Repo, RepoDataSet}

import scala.xml.{Elem, NodeSeq, PrettyPrinter}

object OaiPmh extends Controller with BaseXTools {

  val PRETTY = new PrettyPrinter(300, 5)

  def service(accessKey: String) = Action(parse.anyContent) {
    implicit request =>
      Logger.info(s"Request arrived, not yet checking access key [$accessKey]")
      Ok(Service(request.queryString, request.uri))
  }

  object RepoBridge {

    case class Format(prefix: String, schema: String, namespace: String)

    val pageSize = NarthexConfig.OAI_PMH_PAGE_SIZE

    // todo: implement this!
    def getFormats(identifier: Option[String]): Seq[RepoBridge.Format] = {
      throw new RuntimeException("Not implemented")
    }

    def getDataSets: Seq[RepoDataSet] = {
      Repo.repo.getDataSets
    }

    def exists(set: String, prefix: String): Boolean = {
      getDataSets.exists(ds => ds.spec == set && ds.prefix == prefix)
    }

    def getFirstToken(set: String, prefix: String, headersOnly: Boolean, from: Option[DateTime], until: Option[DateTime]): Option[String] = {
      val fileRepo = Repo.repo.fileRepo(s"${set}__$prefix")
      fileRepo.recordRepo.createHarvest(headersOnly, from, until)
    }

    def getHarvestValues(token: String): (Option[NodeSeq], Option[String]) = {
      Repo.repo.getHarvest(token)
    }

    def getRecord(set: String, format: String, identifier: String): Option[NodeSeq] = {
      val fileName = s"${set}__$format"
      Repo.repo.fileRepoOption(fileName) match {
        case Some(fileRepo) =>
          Some(fileRepo.recordRepo.recordPmh(identifier))
        case None =>
          None
      }
    }
  }

  object Service {

    def apply(queryString: Map[String, Seq[String]], requestURL: String) = {
      val oaiPmhService = new Service(queryString, requestURL)
      oaiPmhService.handleRequest
    }

    object QueryKey {
      val VERB = "verb"
      val IDENTIFIER = "identifier"
      val METADATA_PREFIX = "metadataPrefix"
      val SET = "set"
      val FROM = "from"
      val UNTIL = "until"
      val RESUMPTION_TOKEN = "resumptionToken"
      val BODY = "body"
      val ALL_QUERY_KEYS = List(VERB, IDENTIFIER, METADATA_PREFIX, SET, FROM, UNTIL, RESUMPTION_TOKEN, BODY)
    }

    object Verb {
      val LIST_SETS = "ListSets"
      val LIST_METADATA_FORMATS = "ListMetadataFormats"
      val LIST_IDENTIFIERS = "ListIdentifiers"
      val LIST_RECORDS = "ListRecords"
      val GET_RECORD = "GetRecord"
      val IDENTIFY = "Identify"
    }

  }

  class Service(queryParams: Map[String, Seq[String]], requestURL: String) {

    object VerbHandling {

      def apply(verb: String): Elem = {
        val elem = VERB_FUNCTIONS.find(_.verb.equalsIgnoreCase(verb)) match {
          case Some(verbFunction) =>
            verbFunction.handlerFunction.apply(pmhRequest(verb))
          case None =>
            errorResponse("badVerb")
        }
        // todo: overkill!
        scala.xml.XML.loadString(PRETTY.format(elem))
      }

      case class VerbHandler(verb: String, handlerFunction: PmhRequest => Elem)

      val VERB_FUNCTIONS = List(
        VerbHandler(Verb.LIST_SETS, listSets),
        VerbHandler(Verb.LIST_METADATA_FORMATS, listMetadataFormats),
        VerbHandler(Verb.LIST_IDENTIFIERS, listIdentifiers),
        VerbHandler(Verb.LIST_RECORDS, listRecords),
        VerbHandler(Verb.GET_RECORD, getRecord),
        VerbHandler(Verb.IDENTIFY, identify)
      )
    }

    def handleRequest: Elem = {
      if (requestOk) {
        val response: Elem = try {
          val maybeVerb: Option[String] = queryParams.get(QueryKey.VERB).map(_.head)
          maybeVerb match {
            case Some(verb) =>
              VerbHandling(verb)
            case None =>
              errorResponse("badVerb")
          }
        }
        catch {
          case ope: OaiPmhException =>
            errorResponse(ope.error, Some(ope))
          case e: Exception =>
            errorResponse("badArgument", Some(e))
        }
        response
      }
      else {
        errorResponse("badArgument")
      }
    }

    def pmhRequest(verb: String): PmhRequest = {
      def paramString(key: String) = queryParams.get(key).map(_.headOption.getOrElse(""))
      def parseDate(dateString: String): Option[DateTime] = {
        try {
          Some(fromXSDDateTime(dateString))
        }
        catch {
          case t: Throwable =>
            Logger.error("Bad date", t)
            None
        }
      }
      def paramDate(key: String) = paramString(key).map(parseDate).getOrElse(None)
      def paramResumptionToken(key: String): Option[String] = paramString(key)
      PmhRequest(
        verb,
        paramString("set"),
        paramDate("from"), paramDate("until"),
        paramString("metadataPrefix"),
        paramString("identifier"),
        paramResumptionToken("resumptionToken")
      )
    }

    def requestOk: Boolean = {
      if (!queryParams.contains(QueryKey.VERB)) return false
      if (queryParams.values.exists(value => value.length > 1)) return false
      if (queryParams.keys.filterNot(QueryKey.ALL_QUERY_KEYS.contains).nonEmpty) return false
      Seq(queryParams.get("from").headOption, queryParams.get("until").headOption).filterNot(_.isEmpty).foreach {
        dateString =>
          try {
            fromXSDDateTime(dateString.get.head)
          }
          catch {
            case t: Throwable =>
              Logger.error("Bad date", t)
              return false
          }
      }
      true
    }

    // handlers ====

    def identify(request: PmhRequest): Elem = {
      <OAI-PMH
      xmlns="http://www.openarchives.org/OAI/2.0/"
      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
        <responseDate>{currentDate}</responseDate>
        <request verb="Identify">{requestURL}</request>
        <Identify>
          <repositoryName>{OAI_PMH_REPOSITORY_NAME}</repositoryName>
          <baseURL>{requestURL}</baseURL>
          <protocolVersion>2.0</protocolVersion>
          <adminEmail>{OAI_PMH_ADMIN_EMAIL}</adminEmail>
          <earliestDatestamp>{OAI_PMH_EARLIEST_DATE_STAMP}</earliestDatestamp>
          <deletedRecord>no</deletedRecord>
          <granularity>YYYY-MM-DDThh:mm:ssZ</granularity>
          <compression>deflate</compression>
          <description>
            <oai-identifier
            xmlns="http://www.openarchives.org/OAI/2.0/oai-identifier"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/oai-identifier http://www.openarchives.org/OAI/2.0/oai-identifier.xsd">
              <scheme>oai</scheme>
              <repositoryIdentifier>{OAI_PMH_REPOSITORY_IDENTIFIER}</repositoryIdentifier>
              <delimiter>:</delimiter>
              <sampleIdentifier>{OAI_PMH_SAMPLE_IDENTIFIER}</sampleIdentifier>
            </oai-identifier>
          </description>
        </Identify>
      </OAI-PMH>
    }

    def listSets(request: PmhRequest): Elem = {
      val dataSets = RepoBridge.getDataSets
      if (dataSets.isEmpty) return errorResponse("noSetHierarchy")
      <OAI-PMH
      xmlns="http://www.openarchives.org/OAI/2.0/"
      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
        <responseDate>{currentDate}</responseDate>
        <request verb="ListSets">{requestURL}</request>
        <ListSets>
          {for (set <- dataSets) yield
          <set>
            <setSpec>{set.spec}</setSpec>
            <setName>{set.name}</setName>
            <setDescription>
              <description></description>
              <totalRecords>{set.totalRecords}</totalRecords>
              <dataProvider>{set.dataProvider}</dataProvider>
            </setDescription>
          </set>}
        </ListSets>
      </OAI-PMH>
    }

    def listMetadataFormats(request: PmhRequest): Elem = {
      <OAI-PMH
      xmlns="http://www.openarchives.org/OAI/2.0/"
      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
        <responseDate>{currentDate}</responseDate>{request.identifier match {
        case Some(identifier) =>
          <request verb="ListMetadataFormats" identifier={identifier}>{requestURL}</request>
        case None =>
          <request verb="ListMetadataFormats">{requestURL}</request>
      }}<ListMetadataFormats>
        {for (format <- RepoBridge.getFormats(request.identifier)) yield <metadataFormat>
          <metadataPrefix>{format.prefix}</metadataPrefix>
          <schema>{format.schema}</schema>
          <metadataNamespace>{format.namespace}</metadataNamespace>
        </metadataFormat>}
      </ListMetadataFormats>
      </OAI-PMH>
    }

    def startOrResume(request: PmhRequest, headersOnly: Boolean) : (Option[NodeSeq], Option[String]) = {
      request.resumptionToken match {
        case Some(previousToken) =>
          RepoBridge.getHarvestValues(previousToken)
        case None =>
          val set = request.set.getOrElse(throw new BadArgumentException("No set provided"))
          val prefix = request.metadataPrefix.getOrElse(throw new BadArgumentException("No metadataPrefix provided"))
          if (!RepoBridge.exists(set, prefix)) throw new DataSetNotFoundException(s"Set not found: [$set] [$prefix]")
          val firstToken = RepoBridge.getFirstToken(set, prefix, headersOnly, request.from, request.until)
          RepoBridge.getHarvestValues(firstToken.get)
      }
    }

    def listIdentifiers(request: PmhRequest): Elem = {

      val (headers: Option[NodeSeq], token: Option[String]) = startOrResume(request, headersOnly = true)

      <OAI-PMH
      xmlns="http://www.openarchives.org/OAI/2.0/"
      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
        <responseDate>{currentDate}</responseDate>
        <request
          verb={request.verb}
          from={emptyOrDate(request.from)}
          until={emptyOrDate(request.until)}
          metadataPrefix={emptyOrString(request.metadataPrefix)}
        >{requestURL}</request>
        <ListIdentifiers>
          {headers.get}
          {
            token match {
              case Some(tok) =>
                <resumptionToken>{tok}</resumptionToken>
              case None =>
            }
          }
        </ListIdentifiers>
      </OAI-PMH>
    }

    def listRecords(request: PmhRequest): Elem = {

      val (records: Option[NodeSeq], token: Option[String]) = startOrResume(request, headersOnly = false)

      <OAI-PMH
      xmlns="http://www.openarchives.org/OAI/2.0/"
      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
        <responseDate>{currentDate}</responseDate>
        <request
        verb={request.verb}
        from={emptyOrDate(request.from)}
        until={emptyOrDate(request.until)}
        metadataPrefix={emptyOrString(request.metadataPrefix)}
        >{requestURL}</request>
        <ListRecords>
          {records.get}
          {
            token match {
              case Some(tok) =>
                <resumptionToken>{tok}</resumptionToken>
              case None =>
            }
          }
        </ListRecords>
      </OAI-PMH>
    }

    def fromPmhIdToHubId(pmhId: String): String = {
      val pmhIdExtractor = """^oai:(.*?)_(.*?):(.*)$""".r
      val pmhIdExtractor(orgId, spec, localId) = pmhId
      "%s_%s_%s".format(orgId, spec, localId)
    }

    def getRecord(request: PmhRequest): Elem = {

      val set = request.set.getOrElse(throw new BadArgumentException("No set provided"))
      val prefix = request.metadataPrefix.getOrElse(throw new BadArgumentException("No metadataPrefix provided"))
      if (!RepoBridge.exists(set, prefix)) throw new DataSetNotFoundException(s"Set not found: [$set] [$prefix]")
      val identifier = request.identifier.getOrElse(throw new BadArgumentException("No identifier provided"))
      val maybeRecord: Option[NodeSeq] = RepoBridge.getRecord(set, prefix, identifier)
      val record = maybeRecord.getOrElse(throw new RecordNotFoundException(s"No record for identifier: [$identifier]"))

      <OAI-PMH
      xmlns="http://www.openarchives.org/OAI/2.0/"
      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
        <responseDate>{currentDate}</responseDate>
        <request verb={request.verb} identifier={identifier} metadataPrefix={emptyOrString(request.metadataPrefix)}>{requestURL}</request>
        <GetRecord>
          {record}
        </GetRecord>
      </OAI-PMH>
    }

    def errorResponse(errorCode: String, exception: Option[Exception] = None): Elem = {
      exception.map(Logger.error(errorCode, _))

      def error(message: String): NodeSeq = <error code={errorCode}>{message}</error>

      val errorElement = errorCode match {
        case "badArgument" => error(
          "The request includes illegal arguments, is missing required arguments, includes a repeated argument, or values for arguments have an illegal syntax."
        )
        case "badResumptionToken" => error(
          "The value of the resumptionToken argument is invalid or expired."
        )
        case "badVerb" => error(
          "Value of the verb argument is not a legal OAI-PMH verb, the verb argument is missing, or the verb argument is repeated."
        )
        case "cannotDisseminateFormat" => error(
          "The metadata format identified by the value given for the metadataPrefix argument is not supported by the item or by the repository."
        )
        case "idDoesNotExist" => error(
          "The value of the identifier argument is unknown or illegal in this repository."
        )
        case "noMetadataFormats" => error(
          "There are no metadata formats available for the specified item."
        )
        case "noRecordsMatch" => error(
          "The combination of the values of the from, until, set and metadataPrefix arguments results in an empty list."
        )
        case "noSetHierarchy" => error(
          "This repository does not support sets or no sets are publicly available for this repository."
        )
        case _ => error(
          "Unknown Error Code"
        )
      }

      <OAI-PMH
      xmlns="http://www.openarchives.org/OAI/2.0/"
      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
        <responseDate>{currentDate}</responseDate>
        <request>{requestURL}</request>
        {errorElement}
      </OAI-PMH>
    }


    def emptyOrDate(value: Option[DateTime]) = value.map(toXSDString).getOrElse("")

    def emptyOrString(value: Option[String]) = value.getOrElse("")

    def currentDate = toXSDString(new DateTime())

    def dateString(date: DateTime): String = if (date != null) toXSDString(date) else ""

  }

  abstract class OaiPmhException(s: String, t: Throwable) extends Exception(s, t) {
    val error: String
  }

  class AccessKeyException(s: String, t: Throwable) extends OaiPmhException(s, t) {
    def this(s: String) = this(s, null)

    val error = "cannotDisseminateFormat"
  }

  class BadArgumentException(s: String, throwable: Throwable) extends OaiPmhException(s, throwable) {
    def this(s: String) = this(s, null)

    val error = "badArgument"
  }

  class DataSetNotFoundException(s: String, throwable: Throwable) extends OaiPmhException(s, throwable) {
    def this(s: String) = this(s, null)

    val error = "noRecordsMatch"
  }

  class RecordNotFoundException(s: String, throwable: Throwable) extends OaiPmhException(s, throwable) {
    def this(s: String) = this(s, null)

    val error = "noRecordsMatch"
  }

  class RecordParseException(s: String, throwable: Throwable) extends OaiPmhException(s, throwable) {
    def this(s: String) = this(s, null)

    val error = "cannotDisseminateFormat"
  }

  class ResumptionTokenNotFoundException(s: String, throwable: Throwable) extends OaiPmhException(s, throwable) {
    def this(s: String) = this(s, null)

    val error = "badResumptionToken"
  }

  class InvalidIdentifierException(s: String, throwable: Throwable) extends OaiPmhException(s, throwable) {
    def this(s: String) = this(s, null)

    val error = "idDoesNotExist"
  }

  case class PmhRequest
  (
    verb: String,
    set: Option[String],
    from: Option[DateTime],
    until: Option[DateTime],
    metadataPrefix: Option[String],
    identifier: Option[String],
    resumptionToken: Option[String])

}

