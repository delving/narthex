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

package services

import java.security.MessageDigest

import play.Logger

import scala.collection.mutable
import scala.io.Source
import scala.xml.pull._
import scala.xml.{MetaData, NamespaceBinding}

trait RecordHandling {
  val ID_PLACEHOLDER = "__id__"

  class RecordParser(val recordRoot: String, val uniqueId: String) {
    val path = new mutable.Stack[(String, StringBuilder)]

    def parse(source: Source, output: String => Unit, totalRecords: Int, progress: Int => Unit) = {
      val events = new XMLEventReader(source)
      var percentWas = -1
      var lastProgress = 0l
      var recordCount = 0L
      var depth = 0
      var recordText = new mutable.StringBuilder()
      var uniqueIdAttribute: String = null
      var startElement: Option[String] = None

      def indent() = {
        var countdown = depth
        while (countdown > 0) {
          recordText.append(' ')
          countdown -= 1
        }
      }

      def sendProgress(): Unit = {
        val realPercent = ((recordCount * 100) / totalRecords).toInt
        val percent = if (realPercent > 0) realPercent else 1
        if (percent > percentWas && (System.currentTimeMillis() - lastProgress) > 333) {
          progress(percent)
          percentWas = percent
          lastProgress = System.currentTimeMillis()
        }
      }

      def push(tag: String, attrs: MetaData, scope: NamespaceBinding) = {
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
              if (pathString == uniqueId) uniqueIdAttribute = " id=\"" + attr.value + "\""
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
        else if (string == recordRoot) {
          recordText.append(s"<narthex$ID_PLACEHOLDER$scope>\n")
          depth = 1
          startElement = Some(startElementString(tag, attrs))
          findUniqueId(attrs)
        }
      }

      def addFieldText(text: String) = {
        if (depth > 0) {
          val fieldText = path.head._2
          fieldText.append(text)
        }
      }

      def pop(tag: String) = {
        val string = pathString
        val fieldText = path.head._2
        val text = FileHandling.crunchWhitespace(fieldText.toString())
        fieldText.clear()
        path.pop()
        if (depth > 0) {
          if (string == recordRoot) {
            recordCount += 1
            sendProgress()
            indent()
            recordText.append(s"</$tag>\n")
            val recordWithId = recordText.toString().replace(ID_PLACEHOLDER, uniqueIdAttribute)
            output(s"$recordWithId</narthex>\n")
            recordText.clear()
            depth = 0
          }
          else {
            if (string == uniqueId) uniqueIdAttribute = " id=\"" + text + "\""
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
      }

      while (events.hasNext) {

        events.next() match {

          case EvElemStart(pre, label, attrs, scope) => push(FileHandling.tag(pre, label), attrs, scope)

          case EvText(text) => addFieldText(text)

          case EvEntityRef(entity) => addFieldText(s"&$entity;")

          case EvElemEnd(pre, label) => pop(FileHandling.tag(pre, label))

          case EvComment(text) => FileHandling.stupidParser(text, entity => addFieldText(s"&$entity;"))

          case x =>
            Logger.warn("EVENT? " + x) // todo: record these in an error file for later
        }
      }

      progress(100)
    }

    def pathString = "/" + path.reverse.map(_._1).mkString("/")

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

}
