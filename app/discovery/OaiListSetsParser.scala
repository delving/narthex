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

package discovery

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.xml._
import play.api.Logger
import play.api.libs.ws.WSClient

/**
 * Parser for OAI-PMH ListSets responses.
 *
 * Fetches and parses ListSets XML from an OAI-PMH endpoint,
 * extracting set information including Dublin Core metadata
 * from setDescription elements.
 */
@Singleton
class OaiListSetsParser @Inject()(wsClient: WSClient)(implicit ec: ExecutionContext) {

  private val logger = Logger(getClass)

  // Timeout for HTTP requests
  private val REQUEST_TIMEOUT = 60.seconds

  /**
   * Raw set data parsed from ListSets response.
   * Status and matching are applied later by the discovery service.
   */
  case class RawParsedSet(
    setSpec: String,
    setName: String,
    title: Option[String],
    description: Option[String]
  )

  /**
   * Fetch and parse ListSets from an OAI-PMH endpoint.
   *
   * @param baseUrl  The OAI-PMH base URL
   * @return Either an error message or a list of parsed sets
   */
  def fetchListSets(baseUrl: String): Future[Either[String, List[RawParsedSet]]] = {
    // Build ListSets URL
    val separator = if (baseUrl.contains("?")) "&" else "?"
    val listSetsUrl = s"${baseUrl.stripSuffix("/")}${separator}verb=ListSets"

    logger.info(s"Fetching ListSets from: $listSetsUrl")

    wsClient.url(listSetsUrl)
      .withRequestTimeout(REQUEST_TIMEOUT)
      .get()
      .map { response =>
        if (response.status == 200) {
          parseListSets(response.body)
        } else {
          val errorMsg = s"HTTP ${response.status}: ${response.statusText}"
          logger.error(s"ListSets request failed: $errorMsg")
          Left(errorMsg)
        }
      }
      .recover { case e: Exception =>
        val errorMsg = s"Request failed: ${e.getMessage}"
        logger.error(s"ListSets request error: $errorMsg", e)
        Left(errorMsg)
      }
  }

  /**
   * Parse an OAI-PMH ListSets XML response.
   *
   * Expected structure:
   * <OAI-PMH>
   *   <ListSets>
   *     <set>
   *       <setSpec>enb_05.documenten</setSpec>
   *       <setName>documenten</setName>
   *       <setDescription>
   *         <oai_dc:dc xmlns:oai_dc="..." xmlns:dc="...">
   *           <dc:title><![CDATA[Documenten]]></dc:title>
   *           <dc:description><![CDATA[Documenten, Description here]]></dc:description>
   *         </oai_dc:dc>
   *       </setDescription>
   *     </set>
   *   </ListSets>
   * </OAI-PMH>
   */
  private def parseListSets(xmlString: String): Either[String, List[RawParsedSet]] = {
    try {
      val xml = XML.loadString(xmlString)

      // Check for OAI-PMH error response
      val errorNode = xml \\ "error"
      if (errorNode.nonEmpty) {
        val errorCode = (errorNode.head \ "@code").text
        val errorMessage = errorNode.head.text
        return Left(s"OAI-PMH error [$errorCode]: $errorMessage")
      }

      // Parse all set elements
      val sets = (xml \\ "set").map { setNode =>
        val setSpec = (setNode \ "setSpec").text.trim
        val setName = (setNode \ "setName").text.trim

        // Extract title and description from setDescription/oai_dc:dc
        val (title, description) = extractDublinCore(setNode \ "setDescription")

        RawParsedSet(
          setSpec = setSpec,
          setName = setName,
          title = title,
          description = description
        )
      }.toList.filter(_.setSpec.nonEmpty)  // Filter out empty setSpecs

      logger.info(s"Parsed ${sets.length} sets from ListSets response")
      Right(sets)

    } catch {
      case e: SAXParseException =>
        val errorMsg = s"XML parsing error at line ${e.getLineNumber}: ${e.getMessage}"
        logger.error(errorMsg, e)
        Left(errorMsg)
      case e: Exception =>
        val errorMsg = s"Failed to parse ListSets response: ${e.getMessage}"
        logger.error(errorMsg, e)
        Left(errorMsg)
    }
  }

  /**
   * Extract dc:title and dc:description from a setDescription element.
   * Handles various namespace prefixes and CDATA sections.
   */
  private def extractDublinCore(descriptionNodes: NodeSeq): (Option[String], Option[String]) = {
    if (descriptionNodes.isEmpty) {
      return (None, None)
    }

    val descNode = descriptionNodes.head

    // Try to find dc elements with various namespace approaches
    val title = extractDcElement(descNode, "title")
    val description = extractDcElement(descNode, "description")

    (title, description)
  }

  /**
   * Extract a Dublin Core element value, handling namespace variations.
   * Looks for: dc:elementName, oai_dc:elementName, or just elementName
   */
  private def extractDcElement(parent: Node, elementName: String): Option[String] = {
    // Try various ways to find the element
    val candidates = Seq(
      // Direct child with dc: prefix (namespace-aware)
      (parent \\ elementName).headOption,
      // Look for elements ending with the name (handles prefixes)
      parent.descendant.find { n =>
        n.isInstanceOf[Elem] && n.label == elementName
      },
      // Look in any dc namespace
      parent.descendant.find { n =>
        n.isInstanceOf[Elem] &&
        n.label == elementName &&
        (n.namespace == "http://purl.org/dc/elements/1.1/" ||
         n.prefix == "dc")
      }
    )

    candidates.flatten.headOption
      .map(_.text.trim)
      .filter(_.nonEmpty)
  }
}
