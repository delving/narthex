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

  val SUFFIXES = List(".xml.gz", ".xml")

  def getSuffix(uploadedFileName: String) = {
    val suffix = SUFFIXES.filter(suf => uploadedFileName.endsWith(suf))
    if (suffix.isEmpty) "" else suffix.head
  }

  def stripSuffix(uploadedFileName: String) = {
    val suffix = getSuffix(uploadedFileName)
    uploadedFileName.substring(0, uploadedFileName.length - suffix.length)
  }
}
