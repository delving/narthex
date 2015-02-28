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

import dataset.SourceRepo.SourceFacts
import eu.delving.metadata.StringUtil
import play.Logger
import services.StringHandling._
import services._

import scala.collection.mutable
import scala.io.Source
import scala.xml.pull._
import scala.xml.{MetaData, NamespaceBinding, TopScope}

object PocketParser {

  case class Pocket(id: String, text: String, namespaces: Map[String, String]) {

    def textBytes: ByteArrayInputStream = new ByteArrayInputStream(text.getBytes("UTF-8"))

    def writeTo(writer: Writer) = {
      writer.write(text)
      writer.write(s"<!--<$id>-->\n")
    }
  }

  val POCKET_LIST = "pockets"
  val POCKET = "pocket"
  val POCKET_ID = "@id"
  val POCKET_RECORD_ROOT = s"/$POCKET_LIST/$POCKET"
  val POCKET_UNIQUE_ID = s"$POCKET_RECORD_ROOT/$POCKET_ID"
  val POCKET_RECORD_CONTAINER = Some(POCKET_RECORD_ROOT)

  val POCKET_SOURCE_FACTS = SourceFacts("delving-pocket-source", POCKET_RECORD_ROOT, POCKET_UNIQUE_ID, POCKET_RECORD_CONTAINER)

  val SIP_RECORD_TAG = "sip-record"

}

class PocketParser(facts: SourceFacts) {

  import record.PocketParser._

  val path = new mutable.Stack[(String, StringBuilder)]
  val introduceRecord = false // todo: still useful? was deepRecordContainer.exists(_ == recordRootPath)
  val pocketWrap = facts != POCKET_SOURCE_FACTS
  var percentWas = -1
  var lastProgress = 0l
  var recordCount = 0
  var namespaceMap: Map[String, String] = Map.empty

  def parse(source: Source, avoidIds: Set[String], output: Pocket => Unit, progress: ProgressReporter): Int = {
    val events = new NarthexEventReader(source)
    var depth = 0
    var recordText = new mutable.StringBuilder()
    var uniqueId: Option[String] = None
    var startElement: Option[String] = None
    var running = true

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
      }
    }

    def push(tag: String, attrs: MetaData, scope: NamespaceBinding) = {
      def recordNamespace(binding: NamespaceBinding): Unit = {
        if (binding eq TopScope) return
        namespaceMap += binding.prefix -> binding.uri
        recordNamespace(binding.parent)
      }
      recordNamespace(scope)
      def findUniqueId(attrs: List[MetaData]) = attrs.foreach { attr =>
        path.push((s"@${attr.prefixedKey}", new StringBuilder()))
        val ps = pathString
        if (ps == facts.uniqueId) uniqueId = Some(attr.value.toString())
        path.pop()
      }
      path.push((tag, new StringBuilder()))
      val string = pathString
      if (depth > 0) {
        flushStartElement()
        depth += 1
        startElement = Some(startElementString(tag, attrs))
        findUniqueId(attrs.toList)
      }
      else if (string == facts.recordRoot) {
        if (facts.recordContainer.isEmpty || facts.recordContainer.get.equals(facts.recordRoot)) {
          depth = 1
          startElement = Some(startElementString(if (introduceRecord) SIP_RECORD_TAG else tag, attrs))
        }
        findUniqueId(attrs.toList)
      }
      else if (!introduceRecord) facts.recordContainer.foreach { container =>
        if (pathContainer(string) == container) {
          depth = 1
          startElement = Some(startElementString(tag, attrs))
        }
      }
    }

    def addFieldText(text: String) = path.headOption.foreach(_._2.append(text))

    def pop(tag: String) = {
      val string = pathString
      val fieldText = path.head._2
      val text = crunchWhitespace(fieldText.toString())
      fieldText.clear()
      path.pop()
      if (depth > 0) {
        // deep record means check container instead
        val hitRecordRoot = facts.recordContainer.map { container =>
          if (container == facts.recordRoot) {
            string == facts.recordRoot
          }
          else {
            pathContainer(string) == container
          }
        }.getOrElse(string == facts.recordRoot)
        if (hitRecordRoot) {
          flushStartElement()
          indent()
          recordText.append(s"</${if (introduceRecord) SIP_RECORD_TAG else tag}>\n")
          val record = uniqueId.map { idUnclean =>
            if (idUnclean.isEmpty) throw new RuntimeException("Empty unique id!")
            val id = StringUtil.sanitizeId(idUnclean)
            if (avoidIds.contains(id)) None
            else {
              val recordContent = recordText.toString()
              val scope = namespaceMap.view.filter(_._1 != null).map(kv => s"""xmlns:${kv._1}="${kv._2}" """).mkString.trim
              val scopedRecordContent = recordContent.replaceFirst(">", s" $scope>")
              if (pocketWrap) {
                val wrapped = s"""<$POCKET id="$id">\n$scopedRecordContent</$POCKET>\n"""
                Some(Pocket(id, wrapped, namespaceMap))
              }
              else {
                Some(Pocket(id, scopedRecordContent, namespaceMap))
              }
            }
          } getOrElse {
            Logger.error("MISSING ID!")
            throw new RuntimeException("Missing id!")
          }
          record.foreach { r =>
            output(r)
            recordCount += 1
          }
          recordText.clear()
          depth = 0
        }
        else {
          if (string == facts.uniqueId) uniqueId = Some(text)
          indent()
          depth -= 1
          if (startElement.isDefined) {
            val start = startElement.get
            startElement = None
            recordText.append(s"$start$text")
          }
          else if (!text.isEmpty) throw new RuntimeException(s"expected no text for $tag")
          recordText.append(s"</$tag>\n")
        }
      }
      else if (facts.recordContainer.isDefined && string == facts.uniqueId) {
        // if there is a deep record, we find the unique idea outside of it
        uniqueId = Some(text)
      }
    }

    while (events.hasNext && progress.keepGoingAt(recordCount)) {
      events.next() match {
        case EvElemStart(pre, label, attrs, scope) => push(tag(pre, label), attrs, scope)
        case EvText(text) => addFieldText(text)
        case EvEntityRef(entity) => addFieldText(s"&$entity;")
        case EvElemEnd(pre, label) => pop(tag(pre, label))
        case EvComment(text) => stupidParser(text, entity => addFieldText(s"&$entity;"))
        case EvProcInstr(target, text) =>
        case x => Logger.error("EVENT? " + x)
      }
    }
    recordCount
  }

  def pathString = path.reverse.map(_._1).mkString("/", "/", "")

  def pathContainer(string: String) = string.substring(0, string.lastIndexOf("/"))

  def showPath() = Logger.info(pathString)

  def startElementString(tag: String, attrs: MetaData) = {
    val attrString = new mutable.StringBuilder()
    attrs.foreach { attr =>
      val value = "\"" + attr.value.toString() + "\""
      attrString.append(s" ${attr.prefixedKey}=$value")
    }
    s"<$tag$attrString>"
  }


}



