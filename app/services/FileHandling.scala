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

import java.io.{File, FileInputStream, InputStream}
import java.util.zip.{GZIPInputStream, ZipEntry, ZipFile}

import org.apache.commons.io.input.{BOMInputStream, CountingInputStream}

import scala.io.Source

object FileHandling {
  
  abstract class ReadProgress(fileSize: Long) {
    def getPercentRead: Int
  }

  class CountingReadProgress(fileSize: Int, counter: CountingInputStream) extends ReadProgress(fileSize) {
    override def getPercentRead: Int = (100 * counter.getCount) / fileSize
  }

  def xmlSource(file: File): (Source, ReadProgress) = {
    val name = file.getName
    if (name.endsWith(".zip")) {
      val zc = new ZipConcatXML(new ZipFile(file))
      (zc, zc.readProgress)
    }
    else if (name.endsWith(".xml.gz")) {
      val is = new FileInputStream(file)
      val cs = new CountingInputStream(is)
      val gz = new GZIPInputStream(cs)
      val bis = new BOMInputStream(gz)
      (Source.fromInputStream(bis), new CountingReadProgress(file.length().toInt, cs))
    }
    else if (name.endsWith(".xml")) {
      val is = new FileInputStream(file)
      val cs = new CountingInputStream(is)
      val bis = new BOMInputStream(cs)
      (Source.fromInputStream(bis), new CountingReadProgress(file.length().toInt, cs))
    }
    else {
      throw new RuntimeException(s"Unrecognized extension: $name")
    }
  }

  def tag(pre: String, label: String) = if (pre == null || pre.isEmpty) label else s"$pre:$label"

  def stupidParser(comment: String, addEntity: String => Unit) = {
    if (comment == " unknown entity apos; ") {
      addEntity("apos")
    }
  }

  def crunchWhitespace(text: String) = text.replaceAll("\\s+", " ").trim

  def translateEntity(text: String) = text match {
    case "amp" => "&"
    case "quot" => "\""
    case "lt" =>  "<"
    case "gt" =>  ">"
    case "apos" =>   "'"
    case x => ""
  }

//  def createDigest = MessageDigest.getInstance("SHA1")
//  def hex(digest: MessageDigest) = digest.digest().map("%02X" format _).mkString

  private def unzipXML(file: File, inputStream: InputStream) = {
    val stream = if (file.getName.endsWith(".gz")) new GZIPInputStream(inputStream) else inputStream
    new BOMInputStream(stream)
  }

  class ZipConcatXML(val file: ZipFile) extends Source {

    def getFileSize = {
      val e = file.entries()
      var size = 0L
      while (e.hasMoreElements) {
        size += e.nextElement().getSize
      }
      size
    }

    var entries = file.entries()
    var entry: Option[ZipEntry] = None
    var entryInputStream: InputStream = null
    var nextChar : Int = -1
    var charCount = 0L

    class ZipReadProgress(size: Long) extends ReadProgress(size) {
      override def getPercentRead: Int = ((100 * charCount) / size).toInt
    }

    class ZipEntryIterator extends Iterator[Char] {
      override def hasNext: Boolean = {
        entry match {
          case Some(zipEntry) =>
            if (nextChar >= 0) {
              true
            }
            else {
              entry = None
              hasNext
            }

          case None =>
            if (entries.hasMoreElements) {
              // check for .xml
              entry = Some(entries.nextElement())
              entryInputStream = new BOMInputStream(file.getInputStream(entry.get))
              nextChar = entryInputStream.read()
              charCount += 1
              hasNext
            }
            else {
              false
            }
        }
      }

      override def next(): Char = {
        val c = nextChar
        nextChar = entryInputStream.read()
        charCount += 1
        c.toChar
      }
    }

    override protected val iter: Iterator[Char] = new ZipEntryIterator

    def readProgress = new ZipReadProgress(getFileSize)
  }
}
