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

import java.net.URLEncoder

import scala.xml.NodeSeq

object StringHandling {

  def tag(pre: String, label: String) = if (pre == null || pre.isEmpty) label else s"$pre:$label"

  def pathToDirectory(path: String) = path.replace(":", "_").replace("@", "_")


  def stupidParser(comment: String, addEntity: String => Unit) = {
    if (comment == " unknown entity apos; ") {
      addEntity("apos")
    }
  }

  def crunchWhitespace(text: String) = text.replaceAll("\\s+", " ").trim

  def translateEntity(text: String) = text match {
    case "amp" => "&"
    case "quot" => "\""
    case "lt" => "<"
    case "gt" => ">"
    case "apos" => "'"
    case x => ""
  }

  def urlEncodeValue(unencoded: String) = {
    URLEncoder.encode(unencoded, "utf-8")
      .replaceAll("[+]", "%20")
      .replaceAll("[%]28", "(")
      .replaceAll("[%]29", ")")
  }

  def oaipmhPublishFromInfo(info: NodeSeq): Boolean = (info \ "publication" \ "oaipmh").text.trim == "true"

  def sanitizeXml(xml:String) = xml
    .replaceAll("[<]", "&lt;")
    .replaceAll("[&]", "&amp;")
    .replaceAll("[>]", "&gt;")
    .replaceAll("[\"]", "&quot;")
    .replaceAll("[']", "&apos;")

  def sanitizeSparqlLiteral(literal: String) = literal
    .replaceAll("[\"]", "&lt;")

  def slugify(input: String): String = {
    import java.text.Normalizer
    Normalizer.normalize(input, Normalizer.Form.NFD)
      .replaceAll("[^\\w\\s-]", "") // Remove all non-word, non-space or non-dash characters
      .replace('-', ' ')            // Replace dashes with spaces
      .trim                         // Trim leading/trailing whitespace (including what used to be leading/trailing dashes)
      .replaceAll("\\s+", "-")      // Replace whitespace (including newlines and repetitions) with single dashes
      .toLowerCase                  // Lowercase the final results
  }

  val ExtractLocalName = ".*[^\\w](\\w+)".r

}
