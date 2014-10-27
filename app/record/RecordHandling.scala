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

import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.security.MessageDigest

import org.joda.time.DateTime
import play.Logger
import record.RecordHandling.{RawRecord, StoredRecord, TargetConcept, _}
import services.{BaseXTools, FileHandling, ProgressReporter}

import scala.collection.mutable
import scala.io.Source
import scala.xml.pull._
import scala.xml.{MetaData, NamespaceBinding, TopScope}

object RecordHandling {

  val RECORD_LIST_CONTAINER = "pockets"
  val RECORD_CONTAINER = "pocket"
  val RECORD_UNIQUE_ID = "@id"

  case class RawRecord(id: String, hash: String, text: String)

  case class StoredRecord(id: String, mod: DateTime, scope: NamespaceBinding, text: mutable.StringBuilder = new mutable.StringBuilder())

  case class TargetConcept(uri: String, vocabulary: String, prefLabel: String)

}

trait RecordHandling extends BaseXTools {

  class RawRecordParser(recordRootPath: String, uniqueIdPath: String, deepRecordContainer: Option[String] = None) {
    val path = new mutable.Stack[(String, StringBuilder)]
    var percentWas = -1
    var lastProgress = 0l
    var recordCount = 0
    var namespaceMap: Map[String, String] = Map.empty

    def parse(source: Source, avoidIds: Set[String], output: RawRecord => Unit, progressReporter: ProgressReporter): Boolean = {

      val events = new XMLEventReader(source)
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

      def push(tag: String, attrs: MetaData, scope: NamespaceBinding) = {
        def recordNamespace(binding: NamespaceBinding): Unit = {
          if (binding eq TopScope) return
          namespaceMap += binding.prefix -> binding.uri
          recordNamespace(binding.parent)
        }
        recordNamespace(scope)
        def flushStartElement() = {
          if (startElement.isDefined) {
            // todo: check if there was text, if so it's a violation
            indent()
            recordText.append(startElement.get).append("\n")
            startElement = None
          }
        }
        def findUniqueId(attrs: MetaData) = {
          attrs.foreach {
            attr =>
              path.push((s"@${attr.prefixedKey}", new StringBuilder()))
              if (pathString == uniqueIdPath) uniqueId = Some(attr.value.toString())
              path.pop()
          }
        }
        path.push((tag, new StringBuilder()))
        val string = pathString
        if (depth > 0) {
          flushStartElement()
          depth += 1
          startElement = Some(startElementString(tag, attrs))
          findUniqueId(attrs)
        }
        else if (string == recordRootPath) {
          if (deepRecordContainer.isEmpty) {
            depth = 1
            startElement = Some(startElementString(tag, attrs))
          }
          findUniqueId(attrs)
        }
        else deepRecordContainer.foreach { recordContainer =>
          if (pathContainer(string) == recordContainer) {
            depth = 1
            startElement = Some(startElementString(tag, attrs))
          }
        }
      }

      def addFieldText(text: String) = path.headOption.foreach(_._2.append(text))

      def pop(tag: String) = {
        val string = pathString
        val fieldText = path.head._2
        val text = FileHandling.crunchWhitespace(fieldText.toString())
        fieldText.clear()
        path.pop()
        if (depth > 0) {
          // deep record means check container instead
          val hitRecordRoot = deepRecordContainer.map(pathContainer(string) == _).getOrElse(string == recordRootPath)
          if (hitRecordRoot) {
            indent()
            recordText.append(s"</$tag>\n")
            val record = uniqueId.map { id =>
              if (id.isEmpty) throw new RuntimeException("Empty unique id!")
              if (avoidIds.contains(id)) None
              else {
                val recordContent = recordText.toString()
                val contentHash = hashString(recordContent)
                val scope = namespaceMap.view.filter(_._1 != null).map(kv => s"""xmlns:${kv._1}="${kv._2}" """).mkString.trim
                val mod = toXSDString(new DateTime())
                val wrapped = s"""<$RECORD_CONTAINER id="$id" mod="$mod" hash="$contentHash" $scope>\n$recordContent</$RECORD_CONTAINER>\n"""
                Some(RawRecord(id, contentHash, wrapped))
              }
            } getOrElse {
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
            if (string == uniqueIdPath) uniqueId = Some(text)
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
        else if (deepRecordContainer.isDefined && string == uniqueIdPath) {
          // if there is a deep record, we find the unique idea outside of it
          uniqueId = Some(text)
        }
      }

      while (events.hasNext && progressReporter.keepReading(recordCount)) {
        events.next() match {
          case EvElemStart(pre, label, attrs, scope) => push(FileHandling.tag(pre, label), attrs, scope)
          case EvText(text) => addFieldText(text)
          case EvEntityRef(entity) => addFieldText(s"&$entity;")
          case EvElemEnd(pre, label) => pop(FileHandling.tag(pre, label))
          case EvComment(text) => FileHandling.stupidParser(text, entity => addFieldText(s"&$entity;"))
          case x => Logger.error("EVENT? " + x)
        }
      }
      progressReporter.keepWorking
    }

    def pathString = path.reverse.map(_._1).mkString("/", "/", "")

    def pathContainer(string: String) = string.substring(0, string.lastIndexOf("/"))

    def showPath() = Logger.info(pathString)

    def startElementString(tag: String, attrs: MetaData) = {
      val attrString = new mutable.StringBuilder()
      attrs.foreach {
        attr =>
          val value = "\"" + attr.value.toString() + "\""
          attrString.append(s" ${attr.prefixedKey}=$value")
      }
      s"<$tag$attrString>"
    }
  }

  val digest = MessageDigest.getInstance("MD5")

  def bytesOf(record: String) = new ByteArrayInputStream(record.getBytes("UTF-8"))

  def hashString(record: String) = {

    def byteToHex(b: Byte): Char = {
      require(b >= 0 && b <= 15, "Byte " + b + " was not between 0 and 15")
      if (b < 10) {
        ('0'.asInstanceOf[Int] + b).asInstanceOf[Char]
      }
      else {
        ('a'.asInstanceOf[Int] + (b - 10)).asInstanceOf[Char]
      }
    }

    def toHex(bytes: Array[Byte]): String = {
      val buffer = new StringBuilder(bytes.length * 2)
      for (i <- 0 until bytes.length) {
        val b = bytes(i)
        val bi: Int = if (b < 0) b + 256 else b
        buffer append byteToHex((bi >>> 4).asInstanceOf[Byte])
        buffer append byteToHex((bi & 0x0F).asInstanceOf[Byte])
      }
      buffer.toString()
    }

    digest.reset()
    toHex(digest.digest(record.getBytes("UTF-8")))
  }

  class StoredRecordEnricher(pathPrefix: String, mappings: Map[String, TargetConcept]) {

    val ENRICHMENT_NAMESPACES = Seq(
      "rdf" -> "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
      "rdfs" -> "http://www.w3.org/2000/01/rdf-schema#",
      "skos" -> "http://www.w3.org/2004/02/skos/core#"
    )

    case class Frame(tag: String, path: String, text: mutable.StringBuilder = new mutable.StringBuilder())

    def parse(xmlString: String): List[StoredRecord] = {

      val events = new XMLEventReader(Source.fromString(xmlString))
      val stack = new mutable.Stack[Frame]()
      var start: Option[String] = None
      var record: Option[StoredRecord] = None
      var records = List.empty[StoredRecord]
      var recordStart = true

      def startElement(tag: String, attrs: MetaData) = {
        val attrString = new mutable.StringBuilder()
        attrs.foreach { attr =>
          val value = "\"" + attr.value.toString() + "\""
          attrString.append(s" ${attr.prefixedKey}=$value")
        }
        if (recordStart) {
          recordStart = false
          Some(s"<$tag$attrString${record.get.scope}>")
        }
        else {
          Some(s"<$tag$attrString>")
        }
      }

      def pushText(text: String) = if (stack.nonEmpty) stack.head.text.append(text)
      def indent = "  " * (stack.size - 1)
      def value(unencoded: String) = {
        URLEncoder.encode(unencoded, "utf-8")
          .replaceAll("[+]", "%20")
          .replaceAll("[%]28", "(")
          .replaceAll("[%]29", ")")
      }

      while (events.hasNext) events.next() match {

        case EvElemStart(pre, label, attrs, scope) =>
          val tag = FileHandling.tag(pre, label)
          if (tag == RECORD_CONTAINER) {
            val id = attrs.get("id").headOption.map(_.text).getOrElse(throw new RuntimeException(s"$RECORD_CONTAINER element missing id"))
            val mod = attrs.get("mod").headOption.map(_.text).getOrElse(throw new RuntimeException(s"$RECORD_CONTAINER element missing mod"))
            var scopeEnriched = scope
            ENRICHMENT_NAMESPACES.foreach(pn => if (scopeEnriched.getURI(pn._1) == null) scopeEnriched = NamespaceBinding(pn._1, pn._2, scopeEnriched))
            record = Some(StoredRecord(id, fromXSDDateTime(mod), scopeEnriched))
          }
          else record match {
            case Some(r) =>
              val parentPath: String = stack.reverse.map(_.tag).mkString("/")
              val path = s"/$parentPath/$tag"
              start match {
                case Some(startString) =>
                  record.get.text.append(s"$indent$startString\n")
                case None =>
              }
              start = startElement(tag, attrs)
              stack.push(Frame(tag, path))
            case None =>
            // not in the record yet
          }

        case EvText(text) => pushText(text)

        case EvEntityRef(entity) => pushText(s"&$entity;")

        case EvElemEnd(pre, label) =>
          if (stack.isEmpty) {
            if (record.isDefined) {
              records = record.get :: records
              record = None
              recordStart = true
            }
          }
          else {
            val frame = stack.head
            val tag = frame.tag
            val text = frame.text.toString().trim
            if (text.nonEmpty) {
              val path = s"$pathPrefix${frame.path}/${value(text)}"
              val in = indent
              val tagText = mappings.get(path).map { targetConcept =>
                s"""$in${start.get}
                   |$in  <rdf:Description>
                   |$in    <rdfs:label>${frame.text}</rdfs:label>
                   |$in    <skos:prefLabel>${targetConcept.prefLabel}</skos:prefLabel>
                   |$in    <skos:exactMatch rdf:resource="${targetConcept.uri}"/>
                   |$in    <skos:Collection>${targetConcept.vocabulary}</skos:Collection>
                   |$in    <skos:note>From Narthex</skos:note>
                   |$in  </rdf:Description>
                   |$in</$tag>\n""".stripMargin
              } getOrElse {
                s"$in${start.get}${frame.text}</$tag>\n"
              }
              record.get.text.append(tagText)
              start = None
            }
            else start match {
              case Some(startString) =>
                record.get.text.append(s"$indent${startString.replace(">", "/>")}\n")
                start = None
              case None =>
                record.get.text.append(s"$indent</$tag>\n")
            }
            stack.pop()

          }

        case EvComment(text) =>
          FileHandling.stupidParser(text, entity => pushText(s"&$entity;"))

        case x =>
          Logger.warn("EVENT? " + x) // todo: record these in an error file for later
      }

      records.reverse
    }
  }

  def parseStoredRecords(xmlString: String): List[StoredRecord] = {
    val wrappedRecord = scala.xml.XML.loadString(xmlString)
    (wrappedRecord \ RECORD_CONTAINER).map {
      box =>
        StoredRecord(
          id = (box \ "@id").text,
          mod = fromXSDDateTime((box \ "@mod").text),
          box.scope,
          new mutable.StringBuilder((box \ "_").toString())
        )
    }.toList
  }
}
