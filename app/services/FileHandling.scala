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

import java.io.{InputStream, FileInputStream, File}
import java.util.zip.GZIPInputStream
import org.apache.commons.io.input.{BOMInputStream, CountingInputStream}
import scala.io.Source

object FileHandling {

  def source(file: File): Source = Source.fromInputStream(unzipXML(file, new FileInputStream(file)))

  def countingSource(file: File): (CountingInputStream, Source) = {
    val countingStream = new CountingInputStream(new FileInputStream(file))
    val source = Source.fromInputStream(unzipXML(file, countingStream))
    (countingStream, source)
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

  private def unzipXML(file: File, inputStream: InputStream) = {
    val stream = if (file.getName.endsWith(".gz")) new GZIPInputStream(inputStream) else inputStream
    new BOMInputStream(stream)
  }
}
