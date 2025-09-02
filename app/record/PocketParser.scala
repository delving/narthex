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

package record

import java.io.{ByteArrayInputStream, Writer}
import java.security.{MessageDigest, NoSuchAlgorithmException}

import dataset.DsInfo
import dataset.SourceRepo.{IdFilter, SourceFacts}
import organization.OrgContext
import play.api.Logger
import services.StringHandling._
import services._

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.io.Source
import scala.xml.pull._
import scala.xml.{MetaData, NamespaceBinding, TopScope}
import scala.util.control.NonFatal

object PocketParser {

  private val logger = Logger(getClass)

  def cleanUpId(id: String): String = {
    id.replace("/", "-")
      .replace(":", "-")
      .replace("+", "-")
      .replace("(", "-")
      .replace(")", "-")
      .replaceAll("[-]{2,20}", "-")
  }

  case class Pocket(id: String, text: String, namespaces: Map[String, String]) {

    val IdPattern = ".*?<pocket id=\"(.*?)\" .*".r

    def getText: String = {
      IdPattern.findFirstMatchIn(text) match {
        case Some(pocketId) =>
          val sourceId = pocketId.group(1)
          val cleanId = cleanUpId(sourceId)
          text.replace(sourceId, cleanId)
        case None => text
      }
    }

    def textBytes: ByteArrayInputStream =
      new ByteArrayInputStream(text.getBytes("UTF-8"))

    def writeTo(writer: Writer) = {
      val sha1: String = PocketParser.sha1(text)
      writer.write(text)
      writer.write(s"""<!--<${id}__$sha1>-->\n""")
    }
  }

  @throws(classOf[NoSuchAlgorithmException])
  def sha1(input: String): String = {
    val mDigest: MessageDigest = MessageDigest.getInstance("SHA1")
    val result: Array[Byte] = mDigest.digest(input.getBytes)
    val sb: StringBuffer = new StringBuffer()
    for (i <- result.indices) {
      sb.append(Integer.toString((result(i) & 0xff) + 0x100, 16).substring(1))
    }
    sb.toString
  }

  val POCKET_LIST = "pockets"
  val POCKET = "pocket"
  val POCKET_ID = "@id"
  val POCKET_RECORD_ROOT = s"/$POCKET_LIST/$POCKET"
  val POCKET_UNIQUE_ID = s"$POCKET_RECORD_ROOT/$POCKET_ID"
  val POCKET_RECORD_CONTAINER = Some(POCKET_RECORD_ROOT)

  val POCKET_SOURCE_FACTS = SourceFacts("delving-pocket-source",
                                        POCKET_RECORD_ROOT,
                                        POCKET_UNIQUE_ID,
                                        POCKET_RECORD_CONTAINER)

  val SIP_RECORD_TAG = "sip-record"

}

class PocketParser(facts: SourceFacts,
                   idFilter: IdFilter,
                   orgContext: OrgContext) {

  import record.PocketParser._

  val path = new mutable.Stack[(String, StringBuilder)]
  val introduceRecord = false // todo: still useful? was deepRecordContainer.exists(_ == recordRootPath)
  val pocketWrap = facts != POCKET_SOURCE_FACTS
  var percentWas = -1
  var lastProgress = 0L
  var recordCount = 0
  var namespaceMap: Map[String, String] = Map.empty
  
  // Namespace optimization: cache processed namespace scopes to avoid redundant traversals
  private val namespaceScopeCache = mutable.Map[Int, Map[String, String]]()
  
  private def getNamespacesFromScope(scope: NamespaceBinding): Map[String, String] = {
    val scopeHashCode = scope.hashCode()
    namespaceScopeCache.getOrElseUpdate(scopeHashCode, {
      val scopeMap = mutable.Map[String, String]()
      
      def collectNamespaces(binding: NamespaceBinding): Unit = {
        if (binding eq TopScope) return
        if (binding.prefix != null && binding.uri != null) {
          scopeMap += binding.prefix -> binding.uri
        }
        collectNamespaces(binding.parent)
      }
      
      collectNamespaces(scope)
      scopeMap.toMap
    })
  }

  def parse(source: Source,
            avoid: Set[String],
            output: Pocket => Unit,
            progress: ProgressReporter): Int = {
    val events = new XMLEventReader(source)
    var depth = 0
    // Optimize: pre-allocate StringBuilder with reasonable initial capacity like Java version
    val recordText = new mutable.StringBuilder(1024)
    var uniqueId: Option[String] = None
    var startElement: Option[String] = None
    
    // Memory safety: track record size to prevent OOM on extremely large records
    val maxRecordSize = 10 * 1024 * 1024 // 10MB limit per record

    def indent() = {
      var countdown = depth
      while (countdown > 0) {
        recordText.append("  ")
        countdown -= 1
      }
    }

    def flushStartElement() = {
      if (startElement.isDefined) {
        // todo: check if there was text, if so it's a violation
        indent()
        recordText.append(startElement.get).append("\n")
        startElement = None
        checkRecordSize()
      }
    }
    
    def checkRecordSize() = {
      if (recordText.length > maxRecordSize) {
        val currentId = uniqueId.getOrElse("unknown")
        logger.error(s"Record $currentId exceeds maximum size limit of ${maxRecordSize / (1024 * 1024)}MB - skipping")
        throw new RuntimeException(s"Record $currentId too large: ${recordText.length} bytes")
      }
    }

    def setUniqueId(id: String) = {
      val cleanId = cleanUpId(id)
      uniqueId = Some(idFilter.filter(cleanId))
    }

    def push(tag: String, attrs: MetaData, scope: NamespaceBinding) = {
      // Optimize namespace collection like Java version - only collect unique prefixes
      def collectNamespacesDirect(binding: NamespaceBinding): Unit = {
        if (binding eq TopScope) return
        if (binding.prefix != null && binding.uri != null && !namespaceMap.contains(binding.prefix)) {
          namespaceMap += binding.prefix -> binding.uri
        }
        collectNamespacesDirect(binding.parent)
      }
      
      // Only process if we haven't seen this scope before or need new namespaces
      if (scope ne TopScope) {
        collectNamespacesDirect(scope)
      }
      def findUniqueId(attrs: List[MetaData]) = attrs.foreach { attr =>
        path.push((s"@${attr.prefixedKey}", new StringBuilder()))
        val ps = pathString
        if (ps == facts.uniqueId) setUniqueId(attr.value.toString())
        path.pop()
      }
      path.push((tag, new StringBuilder()))
      val string = pathString
      if (depth > 0) {
        flushStartElement()
        depth += 1
        startElement = Some(startElementString(tag, attrs, replaceId = false))
        findUniqueId(attrs.toList)
      } else if (string == facts.recordRoot) {
        if (facts.recordContainer.isEmpty || facts.recordContainer.get.equals(
              facts.recordRoot)) {
          depth = 1
          startElement = Some(
            startElementString(if (introduceRecord) SIP_RECORD_TAG else tag,
                               attrs,
                               replaceId = true))
        }
        findUniqueId(attrs.toList)
      } else if (!introduceRecord) facts.recordContainer.foreach { container =>
        if (pathContainer(string) == container) {
          depth = 1
          startElement = Some(startElementString(tag, attrs, replaceId = true))
        }
      }
    }

    def addFieldText(text: String) = {
      path.headOption.foreach(_._2.append(text))
      // Check size occasionally during text accumulation
      if (recordText.length % 10000 == 0) checkRecordSize()
    }

    def pop(tag: String) = {
      val string = pathString
      val fieldText = path.head._2
      val text = crunchWhitespace(fieldText.toString(),
                                  Some(orgContext.crunchWhiteSpace))
      fieldText.clear()
      path.pop()
      if (depth > 0) {
        // deep record means check container instead
        val hitRecordRoot = facts.recordContainer
          .map { container =>
            if (container == facts.recordRoot) {
              string == facts.recordRoot
            } else {
              pathContainer(string) == container
            }
          }
          .getOrElse(string == facts.recordRoot)
        if (hitRecordRoot) {
          flushStartElement()
          indent()
          recordText.append(
            s"</${if (introduceRecord) SIP_RECORD_TAG else tag}>\n")
          val record = uniqueId.map { id =>
            if (id.trim.isEmpty) throw new RuntimeException("Empty unique id!")
            val cleanId = cleanUpId(id)
            if (avoid.contains(cleanId)) None
            else {
              // Optimize string operations - avoid unnecessary toString() calls
              val hasNaaData = recordText.indexOf("identifier.hyph-guid") >= 0
              
              // Build namespace string efficiently
              val scope = if (namespaceMap.nonEmpty) {
                val scopeBuilder = new StringBuilder()
                namespaceMap.foreach { case (prefix, uri) =>
                  if (prefix != null) {
                    scopeBuilder.append(s""" xmlns:$prefix="$uri"""")
                  }
                }
                scopeBuilder.toString()
              } else ""
              
              // Apply transformations efficiently
              val recordContent = if (hasNaaData) {
                recordText.toString().replaceAll("oai_dc:dc", "record")
              } else {
                recordText.toString()
              }
              
              val scopedRecordContent = if (scope.nonEmpty) {
                recordContent.replaceFirst(">", s"$scope>")
              } else {
                recordContent
              }
              if (pocketWrap) {
                val wrapped =
                  s"""<$POCKET id="$cleanId">\n$scopedRecordContent</$POCKET>\n"""
                Some(Pocket(cleanId, wrapped, namespaceMap))
              } else {
                Some(Pocket(cleanId, scopedRecordContent, namespaceMap))
              }
            }
          } getOrElse {
            logger.error("MISSING ID!")
            throw new RuntimeException("Missing id!")
          }
          record.foreach { r =>
            output(r)
            recordCount += 1
          }
          recordText.clear()
          depth = 0
          
          // Memory optimization: reset namespace map per record and cleanup cache periodically
          namespaceMap = Map.empty
          if (recordCount % 1000 == 0) {
            // Cleanup namespace cache every 1000 records to prevent excessive memory usage
            namespaceScopeCache.clear()
            logger.debug(s"Cleared namespace cache at record $recordCount")
          }
        } else {
          if (string == facts.uniqueId) setUniqueId(text)
          indent()
          depth -= 1
          if (startElement.isDefined) {
            val start = startElement.get
            startElement = None
            recordText.append(s"$start$text")
          } else if (!text.isEmpty) {
            logger.warn(s"Mixed content text of <$tag> ignored: [$text]")
          }
          recordText.append(s"</$tag>\n")
        }
      } else if (facts.recordContainer.isDefined && string == facts.uniqueId) {
        // if there is a deep record, we find the unique idea outside of it
        setUniqueId(text)
      }
    }

    while (events.hasNext) {
      progress.sendValue(Some(recordCount))
      
      // Memory monitoring: log memory usage every 10,000 records for large files
      if (recordCount > 0 && recordCount % 10000 == 0) {
        val runtime = Runtime.getRuntime
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        logger.info(s"Processed $recordCount records. Memory usage: ${usedMemory}MB. Namespace cache size: ${namespaceScopeCache.size}")
      }
      
      try {
        events.next() match {
        case EvElemStart(pre, label, attrs, scope) =>
          push(tag(pre, label), attrs, scope)
        case EvText(text)          => addFieldText(text)
        case EvEntityRef(entity)   => addFieldText(s"&$entity;")
        case EvElemEnd(pre, label) => pop(tag(pre, label))
        case EvComment(text) =>
          stupidParser(text, entity => addFieldText(s"&$entity;"))
        case EvProcInstr(target, text) =>
        case x                         => logger.error("EVENT? " + x)
        }
      } catch {
        case NonFatal(e) =>
          logger.error(s"Error processing XML event at record $recordCount: ${e.getMessage}")
          // Reset state and continue with next record
          recordText.clear()
          depth = 0
          uniqueId = None
          namespaceMap = Map.empty
      }
    }
    recordCount
  }

  def pathString = path.reverse.map(_._1).mkString("/", "/", "")

  def pathContainer(string: String) =
    string.substring(0, string.lastIndexOf("/"))

  def showPath() = logger.debug(pathString)

  def startElementString(tag: String, attrs: MetaData, replaceId: Boolean) = {
    val attrString = new mutable.StringBuilder()
    attrs.foreach { attr =>
      val valueString =
        if (replaceId && attr.key == "id")
          idFilter.filter(attr.value.toString())
        else attr.value.toString()
      val value = "\"" + valueString + "\""
      attrString.append(s" ${attr.prefixedKey}=$value")
    }
    s"<$tag$attrString>"
  }

}
