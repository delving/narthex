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

import scala.io.Source
import scala.xml.pull._
import scala.xml.{MetaData, NamespaceBinding}

trait SkosHandling {

  object SkosVocabulary {

    val XML = "http://www.w3.org/XML/1998/namespace"
    val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    val SKOS = "http://www.w3.org/2004/02/skos/core#"

    def apply(source: Source) = {

      val events = new XMLEventReader(source)

      def attribute(uri: String, label: String, attributes: MetaData, scope: NamespaceBinding) = {
        attributes.get(uri, scope, label) match {
          case None => None
          case Some(x) => Some(x.head.text)
        }
      }

      def about(attributes: MetaData, scope: NamespaceBinding) = attribute(RDF, "about", attributes, scope)
      def lang(attributes: MetaData, scope: NamespaceBinding) = attribute(XML, "lang", attributes, scope)
      def resource(attributes: MetaData, scope: NamespaceBinding) = attribute(RDF, "resource", attributes, scope)

      while (events.hasNext) {
        events.next() match {

          case EvElemStart("rdf", "RDF", attributes, scope) =>
            println("Let's get started")

          case EvElemStart("skos", "ConceptScheme", attributes, scope) =>
            println(s"ConceptScheme about ${about(attributes, scope)}")

          case EvElemStart("skos", "Concept", attributes, scope) =>
            println(s"Concept about ${about(attributes, scope)}")

          case EvElemStart("skos", "definition", attributes, scope) =>
            println(s"Definition lang ${lang(attributes, scope)}")

          case EvElemStart("skos", "prefLabel", attributes, scope) =>
            println(s"Pref Label lang ${lang(attributes, scope)}")

          case EvElemStart("skos", "altLabel", attributes, scope) =>
            println(s"Alt Label lang ${lang(attributes, scope)}")

          case EvElemStart("skos", "inScheme", attributes, scope) =>
            println(s"In Scheme resource ${resource(attributes, scope)}")

          case EvElemStart("skos", "broader", attributes, scope) =>
            println(s"Broader resource ${resource(attributes, scope)}")

          case EvElemStart("skos", "narrower", attributes, scope) =>
            println(s"Narrower resource ${resource(attributes, scope)}")

          case EvElemStart("skos", "topConceptOf", attributes, scope) =>
            println(s"Top Concept resource ${resource(attributes, scope)}")

          case EvElemStart(pre, label, attributes, scope) =>
            println(s"!!!START $label")
            attributes.foreach {
              attr =>
                println(s"Attribute $attr")
            }

          case EvText(text) =>
          //            val crunched = FileHandling.crunchWhitespace(text)
          //            if (!crunched.isEmpty) println(s"Text '$crunched'")

          case EvEntityRef(entity) =>
          //            println(s"Entity $entity")

          case EvElemEnd(pre, label) =>
          //            println(s"End $label")

          case EvComment(text) =>
          //            println(s"Comment $text")
          //            FileHandling.stupidParser(text, string => node.value(FileHandling.translateEntity(string)))

          case x =>
            println("EVENT? " + x)
        }
      }
    }
  }

}
