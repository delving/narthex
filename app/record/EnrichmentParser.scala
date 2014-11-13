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

import org.joda.time.DateTime
import play.Logger
import record.EnrichmentParser.{StoredRecord, TargetConcept}
import record.PocketParser._
import services.StringHandling._
import services.Temporal._
import services._

import scala.collection.mutable
import scala.io.Source
import scala.xml.pull._
import scala.xml.{MetaData, NamespaceBinding}

object EnrichmentParser {

  case class StoredRecord(id: String, mod: DateTime, scope: NamespaceBinding, text: mutable.StringBuilder = new mutable.StringBuilder())

  case class TargetConcept(uri: String, vocabulary: String, prefLabel: String)

  def parseStoredRecords(xmlString: String): List[StoredRecord] = {
    val wrappedRecord = scala.xml.XML.loadString(xmlString)
    (wrappedRecord \ POCKET).map {
      box =>
        StoredRecord(
          id = (box \ "@id").text,
          mod = stringToTime((box \ "@mod").text),
          box.scope,
          new mutable.StringBuilder((box \ "_").toString())
        )
    }.toList
  }

}

class EnrichmentParser(pathPrefix: String, termMappings: Map[String, TargetConcept]) {

  val ENRICHMENT_NAMESPACES = Seq(
    "rdf" -> "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
    "rdfs" -> "http://www.w3.org/2000/01/rdf-schema#",
    "skos" -> "http://www.w3.org/2004/02/skos/core#"
  )

  case class Frame(tag: String, path: String, text: mutable.StringBuilder = new mutable.StringBuilder())

  def parse(xmlString: String): List[StoredRecord] = {
    val events = new NarthexEventReader(Source.fromString(xmlString))
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

    while (events.hasNext) events.next() match {

      case EvElemStart(pre, label, attrs, scope) =>
        val tag = StringHandling.tag(pre, label)
        if (tag == POCKET) {
          val id = attrs.get("id").headOption.map(_.text).getOrElse(throw new RuntimeException(s"$POCKET element missing id"))
          val mod = attrs.get("mod").headOption.map(_.text).getOrElse(throw new RuntimeException(s"$POCKET element missing mod"))
          var scopeEnriched = scope
          ENRICHMENT_NAMESPACES.foreach(pn => if (scopeEnriched.getURI(pn._1) == null) scopeEnriched = NamespaceBinding(pn._1, pn._2, scopeEnriched))
          record = Some(StoredRecord(id, stringToTime(mod), scopeEnriched))
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
            val path = s"$pathPrefix${frame.path}/${urlEncodeValue(text)}"

            Logger.info(s"lookup $path")

            val in = indent
            val tagText = termMappings.get(path).map { targetConcept =>
              s"""$in${start.get}
                   |$in  <rdf:Description rdf:about="$path">
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
        stupidParser(text, entity => pushText(s"&$entity;"))

      case x =>
        Logger.warn("EVENT? " + x) // todo: record these in an error file for later
    }

    records.reverse
  }

}

