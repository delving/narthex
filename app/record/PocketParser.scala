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
import java.security.MessageDigest

import dataset.StagingRepo.StagingFacts
import org.joda.time.DateTime
import play.Logger
import services.StringHandling._
import services.Temporal._
import services._

import scala.collection.mutable
import scala.io.Source
import scala.xml.pull._
import scala.xml.{MetaData, NamespaceBinding, TopScope}

object PocketParser {

  def apply(stagingFacts: StagingFacts) = new PocketParser(stagingFacts.recordRoot, stagingFacts.uniqueId, stagingFacts.deepRecordContainer)

  case class Pocket(id: String, hash: String, text: String, namespaces: Map[String, String]) {

    def textBytes: ByteArrayInputStream = new ByteArrayInputStream(text.getBytes("UTF-8"))

    def path(datasetName:String): String = s"$datasetName/${hash(0)}/${hash(1)}/${hash(2)}/$hash.xml"

  }

  val POCKET_LIST = "pockets"
  val POCKET = "pocket"
  val POCKET_ID = "@id"
  val POCKET_RECORD_ROOT = s"/$POCKET_LIST/$POCKET"
  val POCKET_UNIQUE_ID = s"$POCKET_RECORD_ROOT/$POCKET_ID"
  val POCKET_DEEP_RECORD_ROOT = Some(POCKET_RECORD_ROOT)

  val digest = MessageDigest.getInstance("MD5")

  private def hashString(record: String) = {

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

}

class PocketParser(recordRootPath: String, uniqueIdPath: String, deepRecordContainer: Option[String] = None) {
  import record.PocketParser._
  val path = new mutable.Stack[(String, StringBuilder)]
  var percentWas = -1
  var lastProgress = 0l
  var recordCount = 0
  var namespaceMap: Map[String, String] = Map.empty

  def parse(source: Source, avoidIds: Set[String], output: Pocket => Unit, progressReporter: ProgressReporter): Int = {
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
        if (ps == uniqueIdPath) uniqueId = Some(attr.value.toString())
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
      else if (string == recordRootPath) {
        if (deepRecordContainer.isEmpty) {
          depth = 1
          startElement = Some(startElementString(tag, attrs))
        }
        findUniqueId(attrs.toList)
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
      val text = crunchWhitespace(fieldText.toString())
      fieldText.clear()
      path.pop()
      if (depth > 0) {
        // deep record means check container instead
        val hitRecordRoot = deepRecordContainer.map(pathContainer(string) == _).getOrElse(string == recordRootPath)
        if (hitRecordRoot) {
          flushStartElement()
          indent()
          recordText.append(s"</$tag>\n")
          val record = uniqueId.map { id =>
            if (id.isEmpty) throw new RuntimeException("Empty unique id!")
            if (avoidIds.contains(id)) None
            else {
              val recordContent = recordText.toString()
              val contentHash = hashString(recordContent)
              val scope = namespaceMap.view.filter(_._1 != null).map(kv => s"""xmlns:${kv._1}="${kv._2}" """).mkString.trim
              val mod = timeToString(new DateTime())
              val wrapped = s"""<$POCKET id="$id" mod="$mod" hash="$contentHash" $scope>\n$recordContent</$POCKET>\n"""
              Some(Pocket(id, contentHash, wrapped, namespaceMap))
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



