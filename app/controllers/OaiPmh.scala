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

import java.text.SimpleDateFormat
import java.util.Date

import controllers.OaiPmh.Service.{QueryKey, Verb}
import play.api.Logger
import play.api.mvc._
import services.NarthexConfig._

import scala.xml.{Elem, NodeSeq}

object OaiPmh extends Controller {

  val LOG = Logger("OAI_PMH")
//  val PRETTY = new PrettyPrinter(300, 5)

  def service(accessKey: String) = Action(parse.anyContent) {
    implicit request =>
      LOG.info(s"Request arrived, not yet checking access key [$accessKey]")
      Ok(Service(request.queryString, request.uri))
  }

  object Service {

    val context = null

    def apply(queryString: Map[String, Seq[String]], requestURL: String) = {
      val oaiPmhService = new Service(context, queryString, requestURL)
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

  class Service(context: RecordRepo, queryParams: Map[String, Seq[String]], requestURL: String) {

    object VerbHandling {

      def apply(verb: String) = {
        VERB_FUNCTIONS.find(_.verb.equalsIgnoreCase(verb)) match {
          case Some(verbFunction) =>
            verbFunction.handlerFunction.apply(pmhRequest(verb))
          case None =>
            errorResponse("badVerb")
        }
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
          queryParams.get(QueryKey.VERB).map(_.head) match {
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
      def parseDate(dateString: String): Option[Date] = {
        try {
          Some(DATE_FORMAT.parse(dateString))
        }
        catch {
          case t: Throwable =>
            try {
              Some(UTC_FORMAT.parse(dateString))
            }
            catch {
              case t: Throwable =>
                LOG.warn("Trying to parse invalid date " + dateString)
                None
            }
        }
      }
      def paramDate(key: String) = paramString(key).map(parseDate).getOrElse(None)
      def paramResumptionToken(key: String): Option[ResumptionToken] = paramString(key).map(ResumptionToken(_))
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
      Seq(queryParams.get("from").headOption, queryParams.get("until").headOption).filterNot(_.isEmpty).foreach { date =>
        try {
          DATE_FORMAT.parse(date.get.head)
        }
        catch {
          case t: Throwable =>
            try {
              UTC_FORMAT.parse(date.get.head)
            }
            catch {
              case t: Throwable => return false
            }
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
        <responseDate>
          {currentDate}
        </responseDate>
        <request verb="Identify">
          {requestURL}
        </request>
        <Identify>
          <repositoryName>
            {OAI_PMH_REPOSITORY_NAME}
          </repositoryName>
          <baseURL>
            {requestURL}
          </baseURL>
          <protocolVersion>2.0</protocolVersion>
          <adminEmail>
            {OAI_PMH_ADMIN_EMAIL}
          </adminEmail>
          <earliestDatestamp>
            {OAI_PMH_EARLIEST_DATE_STAMP}
          </earliestDatestamp>
          <deletedRecord>no</deletedRecord>
          <granularity>YYYY-MM-DDThh:mm:ssZ</granularity>
          <compression>deflate</compression>
          <description>
            <oai-identifier
            xmlns="http://www.openarchives.org/OAI/2.0/oai-identifier"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/oai-identifier http://www.openarchives.org/OAI/2.0/oai-identifier.xsd">
              <scheme>oai</scheme>
              <repositoryIdentifier>
                {OAI_PMH_REPOSITORY_IDENTIFIER}
              </repositoryIdentifier>
              <delimiter>:</delimiter>
              <sampleIdentifier>
                {OAI_PMH_SAMPLE_IDENTIFIER}
              </sampleIdentifier>
            </oai-identifier>
          </description>
        </Identify>
      </OAI-PMH>
    }

    def listSets(request: PmhRequest): Elem = {
      val dataSets = context.getDataSets
      if (dataSets.isEmpty) return errorResponse("noSetHierarchy")
      <OAI-PMH
      xmlns="http://www.openarchives.org/OAI/2.0/"
      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
        <responseDate>
          {currentDate}
        </responseDate>
        <request verb="ListSets">
          {requestURL}
        </request>
        <ListSets>
          {for (set <- dataSets) yield
          <set>
            <setSpec>
              {set.spec}
            </setSpec>
            <setName>
              {set.name}
            </setName>
            <setDescription>
              <description>
                {set.description}
              </description>
              <totalRecords>
                {set.totalRecords}
              </totalRecords>
              <dataProvider>
                {set.dataProvider}
              </dataProvider>
            </setDescription>
          </set>}
        </ListSets>
      </OAI-PMH>
    }

    def listMetadataFormats(request: PmhRequest): Elem = {

      val maybeIdentifier = request.identifier

      <OAI-PMH
      xmlns="http://www.openarchives.org/OAI/2.0/"
      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
        <responseDate>
          {currentDate}
        </responseDate>{
          maybeIdentifier match {
            case Some(identifier) =>
              <request verb="ListMetadataFormats" identifier={identifier}>
                {requestURL}
              </request>
            case None =>
              <request verb="ListMetadataFormats">
                {requestURL}
              </request>
          }
        }<ListMetadataFormats>
        {for (format <- context.getFormats(maybeIdentifier)) yield <metadataFormat>
          <metadataPrefix>
            {format.prefix}
          </metadataPrefix>
          <schema>
            {format.schema}
          </schema>
          <metadataNamespace>
            {format.namespace}
          </metadataNamespace>
        </metadataFormat>}
      </ListMetadataFormats>
      </OAI-PMH>
    }

    def listIdentifiers(request: PmhRequest): Elem = {

      val set = request.set.getOrElse(throw new BadArgumentException("No set provided"))
      if (!context.setExists(set)) throw new DataSetNotFoundException(s"Set not found: [$set]")

      val (firstToken: Option[ResumptionToken], headers: NodeSeq) = request.resumptionToken match {
        case Some(previousToken) =>
          (None, context.getHeaders(previousToken))
        case None =>
          val firstToken = context.getFirstIdentifierToken(set, request.metadataPrefix, request.from, request.until)
          (firstToken, context.getHeaders(firstToken))
      }

      val resumptionToken: ResumptionToken = request.resumptionToken match {
        case Some(previousToken) =>
          previousToken.next
        case None =>
          if (firstToken.isEmpty) throw new ResumptionTokenNotFoundException("Missing first token")
          firstToken.get
      }

      <OAI-PMH
      xmlns="http://www.openarchives.org/OAI/2.0/"
      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
        <responseDate>
          {currentDate}
        </responseDate>
        <request verb="{request.verb}" from={emptyOrDate(request.from)} until={emptyOrDate(request.until)} metadataPrefix={emptyOrString(request.metadataPrefix)}>
          {requestURL}
        </request>
        <ListIdentifiers>
          {headers}
          <resumptionToken>
            {resumptionToken}
          </resumptionToken>
        </ListIdentifiers>
      </OAI-PMH>
    }

    def listRecords(request: PmhRequest): Elem = {

      val set = request.set.getOrElse(throw new BadArgumentException("No set provided"))
      if (!context.setExists(set)) throw new DataSetNotFoundException(s"Set not found: [$set]")
      val prefix = request.metadataPrefix.getOrElse(throw new BadArgumentException("No metadataPrefix provided"))

      val (firstToken: Option[ResumptionToken], records: NodeSeq) = request.resumptionToken match {
        case Some(previousToken) =>
          (None, context.getRecords(previousToken))
        case None =>
          val firstToken = context.getFirstRecordToken(set, request.metadataPrefix, request.from, request.until)
          (firstToken, context.getRecords(firstToken))
      }

      val resumptionToken: ResumptionToken = request.resumptionToken match {
        case Some(previousToken) =>
          previousToken.next
        case None =>
          if (firstToken.isEmpty) throw new ResumptionTokenNotFoundException("Missing first token")
          firstToken.get
      }

      <OAI-PMH
      xmlns="http://www.openarchives.org/OAI/2.0/"
      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
        <responseDate>
          {currentDate}
        </responseDate>
        <request verb="ListRecords" from={emptyOrDate(request.from)} until={emptyOrDate(request.until)} metadataPrefix={emptyOrString(request.metadataPrefix)}>
          {requestURL}
        </request>
        <ListRecords>
          {records}
          {resumptionToken}
        </ListRecords>
      </OAI-PMH>
    }

    def fromPmhIdToHubId(pmhId: String): String = {
      val pmhIdExtractor = """^oai:(.*?)_(.*?):(.*)$""".r
      val pmhIdExtractor(orgId, spec, localId) = pmhId
      "%s_%s_%s".format(orgId, spec, localId)
    }

    def getRecord(request: PmhRequest): Elem = {

      val identifier = request.identifier.getOrElse(throw new BadArgumentException("No identifier provided"))
      val maybeRecord: Option[NodeSeq] = context.getRecord(identifier)
      val record = maybeRecord.getOrElse(throw new RecordNotFoundException(s"No record for identifier: [$identifier]"))

      <OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
        <responseDate>
          {currentDate}
        </responseDate>
        <request verb="{request.verb}" identifier={identifier} metadataPrefix={emptyOrString(request.metadataPrefix)}>
          {requestURL}
        </request>
        <GetRecord>
          {record}
        </GetRecord>
      </OAI-PMH>
    }

    def errorResponse(errorCode: String, exception: Option[Exception] = None): Elem = {
      exception.map(LOG.error(errorCode, _))
      <OAI-PMH
      xmlns="http://www.openarchives.org/OAI/2.0/"
      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
        <responseDate>
          {currentDate}
        </responseDate>
        <request>
          {requestURL}
        </request>
          {errorCode match {

        case "badArgument" =>
          <error code="badArgument">
            The request includes illegal arguments, is missing required arguments, includes a repeated argument, or values for arguments have an illegal syntax.
          </error>

        case "badResumptionToken" =>
          <error code="badResumptionToken">
            The value of the resumptionToken argument is invalid or expired.
          </error>

        case "badVerb" =>
          <error code="badVerb">
            Value of the verb argument is not a legal OAI-PMH verb, the verb argument is missing, or the verb argument is repeated.
          </error>

        case "cannotDisseminateFormat" =>
          <error code="cannotDisseminateFormat">
            The metadata format identified by the value given for the metadataPrefix argument is not supported by the item or by the repository.
          </error>

        case "idDoesNotExist" =>
          <error code="idDoesNotExist">
            The value of the identifier argument is unknown or illegal in this repository.
          </error>

        case "noMetadataFormats" =>
          <error code="noMetadataFormats">
            There are no metadata formats available for the specified item.
          </error>

        case "noRecordsMatch" =>
          <error code="noRecordsMatch">
            The combination of the values of the from, until, set and metadataPrefix arguments results in an empty list.
          </error>

        case "noSetHierarchy" =>
          <error code="noSetHierarchy">
            This repository does not support sets or no sets are publicly available for this repository.
          </error> // Should never be used. We only use sets

        case _ =>
          <error code="unknown">
            Unknown Error Code
          </error>
      }}
      </OAI-PMH>
    }


    def emptyOrDate(value: Option[Date]) = value.map(_.toString).getOrElse("")

    def emptyOrString(value: Option[String]) = value.getOrElse("")

    def currentDate = toUtcDateTime(new Date())

    def toUtcDateTime(date: Date): String = UTC_FORMAT.format(date)

    def dateString(date: Date): String = if (date != null) toUtcDateTime(date) else ""

    val UTC_FORMAT: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
    UTC_FORMAT.setLenient(false)

    val DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd")
    DATE_FORMAT.setLenient(false)


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

  //  class MappingNotFoundException(s: String, throwable: Throwable) extends OaiPmhException(s, throwable) {
  //    def this(s: String) = this(s, null)
  //  }

  trait RecordRepo {

    case class DataSet(spec: String, name: String, description: String, totalRecords: Int, dataProvider: String)
    case class Format(prefix: String, schema: String, namespace: String)

    def getRecord(identifier: String): Option[NodeSeq]

    def getFirstIdentifierToken(set: String, format: Option[String], from: Option[Date], until: Option[Date]): ResumptionToken

    def getHeaders(resumptionToken: ResumptionToken): NodeSeq

    def getFirstRecordToken(set: String, format: Option[String], from: Option[Date], until: Option[Date]): ResumptionToken

    def getRecords(resumptionToken: ResumptionToken): NodeSeq

    def setExists(set: String): Boolean

    def getDataSets : Seq[DataSet]

    // if no identifier present list all formats
    // otherwise only list the formats available for the identifier
    def getFormats(identifier: Option[String]) : Seq[Format]
  }

  case class PmhRequest
  (
    verb: String,
    set: Option[String],
    from: Option[Date],
    until: Option[Date],
    metadataPrefix: Option[String],
    identifier: Option[String],
    resumptionToken: Option[ResumptionToken])


  object ResumptionToken {
    private val ResumptionTokenExtractor = """(.+?)::(.+?)::(.+?)::(.+?)::(.+)""".r
    
    def apply(string: String): ResumptionToken = {

      def stringToDate(string: String) = new Date() // todo

      lazy val ResumptionTokenExtractor(set, expiry, totalRecords, pageSize, page) = string
      new ResumptionToken(set, stringToDate(expiry), totalRecords.toInt, pageSize.toInt, page.toInt)
    }

  }

  class ResumptionToken(set: String, expiry: Date, totalRecords:Int, pageSize: Int, page: Int) {
    def next: ResumptionToken = new ResumptionToken(set, expiry, totalRecords, pageSize, page + 1)

    override def toString = s"$set::$expiry::$totalRecords::$pageSize::$page"
  }

}

