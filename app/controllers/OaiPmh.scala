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

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date

import controllers.OaiPmh.PmhVerbType.PmhVerb
import play.api.Logger
import play.api.mvc._

import scala.collection.mutable.ArrayBuffer
import scala.xml.{Elem, NamespaceBinding, PrettyPrinter, TopScope}

object OaiPmh extends Controller {

  def service = Action(parse.anyContent) {
    implicit request =>
      Ok
  }

  class OaiPmhService(queryString: Map[String, Seq[String]], requestURL: String, orgId: String, format: Option[String], accessKey: Option[String]) {

    private val log = Logger("CultureHub")
    val prettyPrinter = new PrettyPrinter(300, 5)

    private val VERB = "verb"
    private val legalParameterKeys = List("verb", "identifier", "metadataPrefix", "set", "from", "until", "resumptionToken", "accessKey", "body")

    /**
     * receive an HttpServletRequest with the OAI-PMH parameters and return the correctly formatted xml as a string.
     */

    def parseRequest: String = {

      if (!isLegalPmhRequest(queryString)) return createErrorResponse("badArgument").toString()

      def pmhRequest(verb: PmhVerb): PmhRequestEntry = createPmhRequest(queryString, verb)

      val response: Elem = try {
        queryString.get(VERB).map(_.head).getOrElse("error") match {
          case "Identify" => processIdentify(pmhRequest(PmhVerbType.IDENTIFY))
          case "ListMetadataFormats" => processListMetadataFormats(pmhRequest(PmhVerbType.List_METADATA_FORMATS))
          case "ListSets" => processListSets(pmhRequest(PmhVerbType.LIST_SETS))
          case "ListRecords" => processListRecords(pmhRequest(PmhVerbType.LIST_RECORDS))
          case "ListIdentifiers" => processListRecords(pmhRequest(PmhVerbType.LIST_IDENTIFIERS), true)
          case "GetRecord" => processGetRecord(pmhRequest(PmhVerbType.GET_RECORD))
          case _ => createErrorResponse("badVerb")
        }
      } catch {
        case ace: AccessKeyException => createErrorResponse("cannotDisseminateFormat", ace)
        case bae: BadArgumentException => createErrorResponse("badArgument", bae)
        case dsnf: DataSetNotFoundException => createErrorResponse("noRecordsMatch", dsnf)
        case rpe: RecordParseException => createErrorResponse("cannotDisseminateFormat", rpe)
        case rtnf: ResumptionTokenNotFoundException => createErrorResponse("badResumptionToken", rtnf)
        case nrm: RecordNotFoundException => createErrorResponse("noRecordsMatch")
        case ii: InvalidIdentifierException => createErrorResponse("idDoesNotExist")
        case e: Exception => createErrorResponse("badArgument", e)
      }
      //    prettyPrinter.format(response) // todo enable pretty printing later again
      response.toString()
    }

    def isLegalPmhRequest(params: Map[String, Seq[String]]): Boolean = {

      // request must contain the verb parameter
      if (!params.contains(VERB)) return false

      // no repeat queryParameters are allowed
      if (params.values.exists(value => value.length > 1)) return false

      // check for illegal queryParameter keys
      if (!(params.keys filterNot (legalParameterKeys contains)).isEmpty) return false

      // check for validity of dates
      Seq(params.get("from").headOption, params.get("until").headOption).filterNot(_.isEmpty).foreach { date =>
        try {
          OaiPmhService.dateFormat.parse(date.get.head)
        } catch {
          case t: Throwable => {
            try {
              OaiPmhService.utcFormat.parse(date.get.head)
            } catch {
              case t: Throwable => return false
            }
          }
        }
      }

      true
    }

    val repositoryName = "fred"

    val adminEmail = "fred@home"

    val earliestDateStamp = "sometime"

    val repositoryIdentifier = "repo"

    val sampleIdentifier = "1"

    def processIdentify(pmhRequestEntry: PmhRequestEntry): Elem = {
      <OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
        <responseDate>
          {OaiPmhService.currentDate}
        </responseDate>
        <request verb="Identify">
          {requestURL}
        </request>
        <Identify>
          <repositoryName>
            {repositoryName}
          </repositoryName>
          <baseURL>
            {requestURL}
          </baseURL>
          <protocolVersion>2.0</protocolVersion>
          <adminEmail>
            {adminEmail}
          </adminEmail>
          <earliestDatestamp>
            {earliestDateStamp}
          </earliestDatestamp>
          <deletedRecord>no</deletedRecord>
          <granularity>YYYY-MM-DDThh:mm:ssZ</granularity>
          <compression>deflate</compression>
          <description>
            <oai-identifier xmlns="http://www.openarchives.org/OAI/2.0/oai-identifier" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/oai-identifier http://www.openarchives.org/OAI/2.0/oai-identifier.xsd">
              <scheme>oai</scheme>
              <repositoryIdentifier>
                {repositoryIdentifier}
              </repositoryIdentifier>
              <delimiter>:</delimiter>
              <sampleIdentifier>
                {sampleIdentifier}
              </sampleIdentifier>
            </oai-identifier>
          </description>
        </Identify>
      </OAI-PMH>
    }

    case class DSet(spec: String, name: String, description: String, totalRecords: Int, dataProvider: String)

    def processListSets(pmhRequestEntry: PmhRequestEntry): Elem = {

      val collections = List.empty[DSet]

      // when there are no collections throw "noSetHierarchy" ErrorResponse
      if (collections.size == 0) return createErrorResponse("noSetHierarchy")

      <OAI-PMH
      xmlns="http://www.openarchives.org/OAI/2.0/"
      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
        <responseDate>
          {OaiPmhService.currentDate}
        </responseDate>
        <request verb="ListSets">
          {requestURL}
        </request>
        <ListSets>
          {for (set <- collections) yield <set>
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

    /**
     * This method can give back the following Error and Exception conditions: idDoesNotExist, noMetadataFormats.
     */

    case class Format(prefix: String, schema: String, namespace: String)

    def processListMetadataFormats(pmhRequestEntry: PmhRequestEntry): Elem = {

      val identifier = pmhRequestEntry.pmhRequestItem.identifier
      val identifierSpec = identifier.split("_").head

      // if no identifier present list all formats
      // otherwise only list the formats available for the identifier
      val allMetadataFormats = List.empty[Format]

      // apply format filter
      val metadataFormats = if (format.isDefined) allMetadataFormats.filter(_.prefix == format.get) else allMetadataFormats

      def formatRequest: Elem = if (!identifier.isEmpty) {
        <request verb="ListMetadataFormats" identifier={identifier}>
          {requestURL}
        </request>
      }
      else {
        <request verb="ListMetadataFormats">
          {requestURL}
        </request>
      }

      <OAI-PMH
      xmlns="http://www.openarchives.org/OAI/2.0/"
      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd"
      >
        <responseDate>
          {OaiPmhService.currentDate}
        </responseDate>{formatRequest}<ListMetadataFormats>
        {for (format <- metadataFormats) yield <metadataFormat>
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

    def processListRecords(pmhRequestEntry: PmhRequestEntry, idsOnly: Boolean = false): Elem = {

      val setName = pmhRequestEntry.getSet
      if (setName.isEmpty) throw new BadArgumentException("No set provided")
      val metadataFormat = pmhRequestEntry.getMetadataFormat

      if (format.isDefined && metadataFormat != format.get) throw new MappingNotFoundException("Invalid format provided for this URL")

      val collection : Harvestable = null
//      val collection = harvestCollectionLookupService.findBySpecAndOrgId(setName, orgId).getOrElse {
//        throw new DataSetNotFoundException("unable to find set: " + setName)
//      }

      val schema: Option[RecordDefinition] = None
      if (!schema.isDefined) {
        throw new MappingNotFoundException("Format %s unknown".format(metadataFormat))
      }
      val (records, totalValidRecords) =
        collection.getRecords(
          metadataFormat,
          pmhRequestEntry.getLastTransferIdx,
          pmhRequestEntry.recordsReturned,
          pmhRequestEntry.pmhRequestItem.from,
          pmhRequestEntry.pmhRequestItem.until
        )

      val recordList = records.toList

      if (recordList.size == 0) throw new RecordNotFoundException(requestURL)

      val from = OaiPmhService.printDate(recordList.head.modified)
      val to = OaiPmhService.printDate(recordList.last.modified)

      val elem: Elem = if (!idsOnly) {
        <OAI-PMH
        xmlns="http://www.openarchives.org/OAI/2.0/"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd"
        >
          <responseDate>
            {OaiPmhService.currentDate}
          </responseDate>
          <request verb="ListRecords" from={from} until={to} metadataPrefix={metadataFormat}>
            {requestURL}
          </request>
          <ListRecords>
            {for (record <- recordList) yield renderRecord(record, metadataFormat, setName)}{pmhRequestEntry.renderResumptionToken(recordList, totalValidRecords)}
          </ListRecords>
        </OAI-PMH>
      } else {
        <OAI-PMH
        xmlns="http://www.openarchives.org/OAI/2.0/"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd"
        >
          <responseDate>
            {OaiPmhService.currentDate}
          </responseDate>
          <request verb="ListIdentifiers" from={from} until={to} metadataPrefix={metadataFormat} set={setName}>
            {requestURL}
          </request>
          <ListIdentifiers>
            {for (record <- recordList) yield <header status={recordStatus(record)}>
            <identifier>
              {OaiPmhService.toPmhId(record.itemId)}
            </identifier>
            <datestamp>
              {record.modified}
            </datestamp>
            <setSpec>
              {setName}
            </setSpec>
          </header>}{pmhRequestEntry.renderResumptionToken(recordList, totalValidRecords)}
          </ListIdentifiers>
        </OAI-PMH>
      }

      prependNamespaces(metadataFormat, schema.get.schemaVersion, collection, elem)
    }

    def fromPmhIdToHubId(pmhId: String): String = {
      val pmhIdExtractor = """^oai:(.*?)_(.*?):(.*)$""".r
      val pmhIdExtractor(orgId, spec, localId) = pmhId
      "%s_%s_%s".format(orgId, spec, localId)
    }

    def processGetRecord(pmhRequestEntry: PmhRequestEntry): Elem = {
      val pmhRequest = pmhRequestEntry.pmhRequestItem
      // get identifier and format from map else throw BadArgument Error
      if (pmhRequest.identifier.isEmpty || pmhRequest.metadataPrefix.isEmpty) return createErrorResponse("badArgument")
      if (pmhRequest.identifier.split(":").length < 2) return createErrorResponse("idDoesNotExist")

      val identifier = fromPmhIdToHubId(pmhRequest.identifier)
      val metadataFormat = pmhRequest.metadataPrefix

      if (format.isDefined && metadataFormat != format.get) throw new MappingNotFoundException("Invalid format provided for this URL")

      val hubId = HubId(identifier)
      // check access rights
      val c : Option[Harvestable] = None
      if (c == None) return createErrorResponse("noRecordsMatch")
      if (!c.get.getVisibleMetadataSchemas(accessKey).exists(_.prefix == metadataFormat)) {
        return createErrorResponse("idDoesNotExist")
      }

      class MetadataCache {
        def findOne(identifier:String) :Option[MetadataItem] = None
      }

      object MetadataCache {
        def get(orgId: String, spec: String, itemType: ItemType) = new MetadataCache()
      }

      val record: MetadataItem = {
        val cache = MetadataCache.get(orgId, hubId.spec, c.get.itemType)
        val mdRecord = cache.findOne(identifier)
        if (mdRecord == None) return createErrorResponse("noRecordsMatch")
        else mdRecord.get
      }

      val collection :Harvestable = null
//      val collection = harvestCollectionLookupService.findBySpecAndOrgId(identifier.split("_")(1), identifier.split("_")(0)).get

      val elem: Elem =
        <OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
          <responseDate>
            {OaiPmhService.currentDate}
          </responseDate>
          <request verb="GetRecord" identifier={OaiPmhService.toPmhId(record.itemId)} metadataPrefix={metadataFormat}>
            {requestURL}
          </request>
          <GetRecord>
            {renderRecord(record, metadataFormat, identifier.split("_")(1))}
          </GetRecord>
        </OAI-PMH>

      prependNamespaces(metadataFormat, record.schemaVersions.getOrElse(metadataFormat, "1.0.0"), collection, elem)
    }

    // todo find a way to not show status namespace when not deleted
    private def recordStatus(record: MetadataItem): String = "" // todo what is the sense of deleted here? do we need to keep deleted references?

    private def renderRecord(record: MetadataItem, metadataPrefix: String, set: String): Elem = {

      val cachedString: String = record.xml.getOrElse(metadataPrefix, throw new RecordNotFoundException(OaiPmhService.toPmhId(record.itemId)))

      // cached records may contain the default namespace, however in our situation that one is already defined in the top scope
      val cleanString = cachedString.replaceFirst("xmlns=\"http://www.w3.org/2000/xmlns/\"", "")

      val response = try {
        val elem: Elem = scala.xml.XML.loadString(cleanString)
        <record>
          <header>
            <identifier>
              {URLEncoder.encode(OaiPmhService.toPmhId(record.itemId), "utf-8").replaceAll("%3A", ":")}
            </identifier>
            <datestamp>
              {OaiPmhService.printDate(record.modified)}
            </datestamp>
            <setSpec>
              {set}
            </setSpec>
          </header>
          <metadata>
            {elem}
          </metadata>
        </record>
      } catch {
        case e: Throwable =>
          log.error("Unable to render record %s with format %s because of %s".format(OaiPmhService.toPmhId(record.itemId), metadataPrefix, e.getMessage), e)
            <record/>
      }
      response
    }

    private def prependNamespaces(metadataFormat: String, schemaVersion: String, collection: Harvestable, elem: Elem): Elem = {
      var mutableElem = elem

      val recordDefinition : RecordDefinition = null
      val formatNamespaces = recordDefinition.allNamespaces
      val globalNamespaces = collection.getNamespaces.map(ns => Namespace(ns._1, ns._2, ""))
      val namespaces = (formatNamespaces ++ globalNamespaces).distinct.filterNot(_.prefix == "xsi")

      def collectNamespaces(ns: NamespaceBinding, namespaces: ArrayBuffer[(String, String)]): ArrayBuffer[(String, String)] = {
        if (ns == TopScope) {
          namespaces += (ns.prefix -> ns.uri)
        } else {
          namespaces += (ns.prefix -> ns.uri)
          collectNamespaces(ns.parent, namespaces)
        }
        namespaces
      }

      val existingNs = collectNamespaces(elem.scope, new ArrayBuffer[(String, String)])

      for (ns <- namespaces) {
        import scala.xml.{Null, UnprefixedAttribute}
        if (ns.prefix == null || ns.prefix.isEmpty) {
          if (!existingNs.exists(p => p._1 == null || p._1.isEmpty)) {
            mutableElem = mutableElem % new UnprefixedAttribute("xmlns", ns.uri, Null)
          }
        } else {
          if (!existingNs.exists(_._1 == ns.prefix)) {
            mutableElem = mutableElem % new UnprefixedAttribute("xmlns:" + ns.prefix, ns.uri, Null)
          }
        }
      }
      mutableElem
    }

    def createPmhRequest(params: Map[String, Seq[String]], verb: PmhVerb): PmhRequestEntry = {

      def getParam(key: String) = params.get(key).map(_.headOption.getOrElse("")).getOrElse("")

      def parseDate(date: String) = try {
        OaiPmhService.dateFormat.parse(date)
      } catch {
        case t: Throwable => {
          try {
            OaiPmhService.utcFormat.parse(date)
          } catch {
            case t: Throwable =>
              log.warn("Trying to parse invalid date " + date)
              new Date()
          }
        }
      }

      val pmh = PmhRequestItem(
        verb,
        getParam("set"),
        params.get("from").map(_.headOption.getOrElse("")).map(parseDate),
        params.get("until").map(_.headOption.getOrElse("")).map(parseDate),
        getParam("metadataPrefix"),
        getParam("identifier")
      )
      PmhRequestEntry(pmh, getParam("resumptionToken"))
    }

    /**
     * This method is used to create all the OAI-PMH error responses to a given OAI-PMH request. The error descriptions have
     * been taken directly from the specifications document for v.2.0.
     */
    def createErrorResponse(errorCode: String, exception: Exception): Elem = {
      log.error(errorCode, exception)
      createErrorResponse(errorCode)
    }

    def createErrorResponse(errorCode: String): Elem = {
      <OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
        <responseDate>
          {OaiPmhService.currentDate}
        </responseDate>
        <request>
          {requestURL}
        </request>{errorCode match {

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

    case class PmhRequestItem(verb: PmhVerb, set: String, from: Option[Date], until: Option[Date], metadataPrefix: String, identifier: String)

    case class PmhRequestEntry(pmhRequestItem: PmhRequestItem, resumptionToken: String) {

      val recordsReturned = 666 // todo: i don't know

      private val ResumptionTokenExtractor = """(.+?):(.+?):(.+?):(.+?):(.+)""".r

      lazy val ResumptionTokenExtractor(set, metadataFormat, recordInt, pageNumber, originalSize) = resumptionToken // set:medataFormat:lastTransferIdx:numberSeen

      def getSet = if (resumptionToken.isEmpty) pmhRequestItem.set else set

      def getMetadataFormat = if (resumptionToken.isEmpty) pmhRequestItem.metadataPrefix else metadataFormat

      def getPagenumber = if (resumptionToken.isEmpty) 1 else pageNumber.toInt

      def getLastTransferIdx = if (resumptionToken.isEmpty) 0 else recordInt.toInt

      def getOriginalListSize = if (resumptionToken.isEmpty) 0 else originalSize.toInt

      def renderResumptionToken(recordList: List[MetadataItem], totalListSize: Long) = {

        val originalListSize = if (getOriginalListSize == 0) totalListSize else getOriginalListSize

        val currentPageNr = if (resumptionToken.isEmpty) getPagenumber else getPagenumber + 1

        val nextIndex = currentPageNr * recordsReturned

        val nextResumptionToken = "%s:%s:%s:%s:%s".format(getSet, getMetadataFormat, nextIndex, currentPageNr, originalListSize)

        val cursor = currentPageNr * recordsReturned
        if (cursor < originalListSize) {
          <resumptionToken expirationDate={OaiPmhService.printDate(new Date())} completeListSize={originalListSize.toString} cursor={cursor.toString}>
            {nextResumptionToken}
          </resumptionToken>
        } else
            <resumptionToken/>
      }

    }

  }

  object OaiPmhService {

    val utcFormat: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
    val dateFormat = new SimpleDateFormat("yyyy-MM-dd")
    utcFormat.setLenient(false)
    dateFormat.setLenient(false)

    def toUtcDateTime(date: Date): String = utcFormat.format(date)

    def currentDate = toUtcDateTime(new Date())

    def printDate(date: Date): String = if (date != null) toUtcDateTime(date) else ""

    /**
     * Turns a hubId (orgId_spec_localId) into a pmhId of the kind oai:kulturnett_kulturit.no:NOMF-00455Q
     */
    def toPmhId(hubId: String) = HubId(hubId).pmhId

  }

  object PmhVerbType extends Enumeration {

    case class PmhVerb(command: String) extends Val(command)

    val LIST_SETS = PmhVerb("ListSets")
    val List_METADATA_FORMATS = PmhVerb("ListMetadataFormats")
    val LIST_IDENTIFIERS = PmhVerb("ListIdentifiers")
    val LIST_RECORDS = PmhVerb("ListRecords")
    val GET_RECORD = PmhVerb("GetRecord")
    val IDENTIFY = PmhVerb("Identify")
  }

  class AccessKeyException(s: String, throwable: Throwable) extends Exception(s, throwable) {
    def this(s: String) = this(s, null)
  }

  class BadArgumentException(s: String, throwable: Throwable) extends Exception(s, throwable) {
    def this(s: String) = this(s, null)
  }

  class DataSetNotFoundException(s: String, throwable: Throwable) extends Exception(s, throwable) {
    def this(s: String) = this(s, null)
  }

  class RecordNotFoundException(s: String, throwable: Throwable) extends Exception(s, throwable) {
    def this(s: String) = this(s, null)
  }

  class MetaRepoSystemException(s: String, throwable: Throwable) extends Exception(s, throwable) {
    def this(s: String) = this(s, null)
  }

  class RecordParseException(s: String, throwable: Throwable) extends Exception(s, throwable) {
    def this(s: String) = this(s, null)
  }

  class ResumptionTokenNotFoundException(s: String, throwable: Throwable) extends Exception(s, throwable) {
    def this(s: String) = this(s, null)
  }

  class InvalidIdentifierException(s: String, throwable: Throwable) extends Exception(s, throwable) {
    def this(s: String) = this(s, null)
  }

  class MappingNotFoundException(s: String, throwable: Throwable) extends Exception(s, throwable) {
    def this(s: String) = this(s, null)
  }

  case class MetadataItem
  (
    modified: Date = new Date(),
    collection: String,
    itemType: String,
    itemId: String,
    xml: Map[String, String], // schemaPrefix -> raw XML string
    schemaVersions: Map[String, String], // schemaPrefix -> schemaVersion
    index: Int,
    invalidTargetSchemas: Seq[String] = Seq.empty,
    systemFields: Map[String, List[String]] = Map.empty
    )

  case class RecordDefinition
  (
    prefix: String,
    schema: String,
    schemaVersion: String,
    namespace: String, // the namespace of the format
    allNamespaces: List[Namespace], // all the namespaces occurring in this format (prefix, schema)
    isFlat: Boolean // is this a flat record definition, i.e. can it be flat?
    ) {
    def getNamespaces = allNamespaces.map(ns => (ns.prefix, ns.uri)).toMap[String, String]
  }

  case class Namespace(prefix: String, uri: String, schema: String)

  case class HubId(orgId: String, spec: String, localId: String) {

    val id = "%s_%s_%s".format(orgId, spec, localId)

    val pmhId = "oai:%s_%s:%s".format(orgId, spec, localId)

    override def toString: String = id
  }

  object HubId {

    val HubIdExtractor = """^(.*?)_(.*?)_(.*)$""".r

    def apply(id: String): HubId = {
      val HubIdExtractor(orgId, spec, localId) = id
      HubId(orgId, spec, localId)
    }

  }
  trait CollectionMetadata {

    /**
     * Name of the collection
     */
    def getName: String

    /**
     * The total records in a collection
     */
    def getTotalRecords: Long

    /**
     * Optional textual description for a collection
     */
    def getDescription: Option[String]

  }

  trait Harvestable extends Collection with CollectionMetadata {

    /**
     * The namespaces that the XML records in this collection make use of
     * @return
     */
    def getNamespaces: Map[String, String]

    /**
     * Fetches the records of the collection
     * @param metadataFormat the metadata format in which to get the records
     * @param position the index at which to start serving the records
     * @param limit the maximum number of records to return
     * @param from optional start date
     * @param until optional until date
     *
     * @return a tuple containing the list of [[ models.MetadataItem ]] as well as the total number of items to be expected
     */
    def getRecords(metadataFormat: String, position: Int, limit: Int, from: Option[Date] = None, until: Option[Date] = None): (List[MetadataItem], Long)

    /**
     * The metadata formats visible for this request
     * @param accessKey an optional accessKey that may give access to more formats
     * @return the set of formats represented by a [[ models.RecordDefinition ]]
     */
    def getVisibleMetadataSchemas(accessKey: Option[String]): Seq[RecordDefinition]

  }
  case class ItemType(itemType: String)

  abstract class Collection {

    // the identifier of this collection. may only contain alphanumericals and dashes
    val spec: String

    // the type of owner
    val ownerType: OwnerType.OwnerType

    // the kind of items in this collection
    val itemType: ItemType

    // the userName of the creator of this collection
    def getCreator: String

    // the owner of this collection. This may be an orgId or userName
    def getOwner: String

  }

  object OwnerType extends Enumeration {
    type OwnerType = Value
    val USER = Value("USER")
    val ORGANIZATION = Value("ORGANIZATION")
  }
}

