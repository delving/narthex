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

import scala.collection.mutable
import scala.io.Source
import scala.xml.pull._
import scala.xml.{MetaData, NamespaceBinding}

trait SkosHandling {

  case class ConceptScheme(about: String) {
    val concepts = mutable.MutableList[Concept]()
    val topConcepts = mutable.MutableList[Concept]()

    override def toString: String =
      s"""
         |ConceptScheme($about):
         |Concepts:
         |${concepts.mkString("")}
         |TopConcepts:
         |${topConcepts.mkString("")}
       """.stripMargin
  }

  case class Concept(about: String) {
    val prefLabels = mutable.MutableList[PrefLabel]()
    val altLabels = mutable.MutableList[AltLabel]()
    val narrowerConcepts = mutable.MutableList[Concept]()
    val broaderConcepts = mutable.MutableList[Concept]()
    val definitions = mutable.MutableList[Definition]()

    override def toString: String =
      s"""
         |Concept($about)
         |  PrefLabels: ${prefLabels.mkString(",")}
         |   AltLabels: ${altLabels.mkString(",")}
         |    Narrower:  ${narrowerConcepts.map(_.prefLabels.head).mkString(",")}
         |   c Broader:  ${broaderConcepts.map(_.prefLabels.head).mkString(",")}
       """.stripMargin
  }

  case class Definition(language: String) {
    var text: String = ""

    override def toString: String = s"""Definition[$language]("$text")"""
  }

  case class PrefLabel(language: String) {
    var text: String = ""

    override def toString: String = s"""PrefLabel[$language]("$text")"""
  }

  case class AltLabel(language: String) {
    var text: String = ""

    override def toString: String = s"""AltLabel[$language]("$text")"""
  }

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

      var activeConceptScheme: Option[ConceptScheme] = None
      var activeConcept: Option[Concept] = None
      var activePrefLabel: Option[PrefLabel] = None
      var activeAltLabel: Option[AltLabel] = None
      var activeDefinition: Option[Definition] = None
      var concepts = new mutable.HashMap[String, Concept]()
      val textBuilder = new mutable.StringBuilder()

      while (events.hasNext) {
        events.next() match {


          case EvElemStart("rdf", "RDF", attributes, scope) =>
            println("starting RDF")

          case EvElemEnd("rdf", "RDF") =>
            textBuilder.clear()
            println("finished RDF")


          case EvElemStart("skos", "ConceptScheme", attributes, scope) =>
            activeConceptScheme = Some(new ConceptScheme(about(attributes, scope).get))
          //            println(s"ConceptScheme about ${about(attributes, scope)}")

          case EvElemEnd("skos", "ConceptScheme") =>
            textBuilder.clear()
          //            println(s"finished ConceptScheme ${activeConceptScheme.get}")


          case EvElemStart("skos", "topConceptOf", attributes, scope) =>
            val r = resource(attributes, scope)
            val target = concepts.get(r.get)
            activeConcept.map { c =>
              activeConceptScheme.map { s =>
                target.map { t =>
                  s.topConcepts += t
                }
              }
            }
          //            println(s"Top Concept resource ${resource(attributes, scope)}")

          case EvElemEnd("skos", "topConceptOf") =>
            textBuilder.clear()
          //            println(s"finished TopConceptOf")

          case EvElemStart("skos", "Concept", attributes, scope) =>
            val concept: Concept = new Concept(about(attributes, scope).get)
            activeConcept = Some(concept)
            concepts.put(concept.about, concept)
            activeConceptScheme.map(_.concepts += concept)
          //            println(s"Concept about ${about(attributes, scope)}")

          case EvElemEnd("skos", "Concept") =>
            textBuilder.clear()
          //            println(s"finished Concept ${activeConcept.get}")


          case EvElemStart("skos", "definition", attributes, scope) =>
            activeConcept.map(c => {
              val definition: Definition = new Definition(lang(attributes, scope).get)
              activeDefinition = Some(definition)
              c.definitions += definition
            })
          //            println(s"Definition lang ${lang(attributes, scope)}")

          case EvElemEnd("skos", "definition") =>
            activeDefinition.map(d => d.text = textBuilder.toString())
            textBuilder.clear()
          //            println(s"finished Definition $activeDefinition")


          case EvElemStart("skos", "prefLabel", attributes, scope) =>
            activeConcept.map(c => {
              val prefLabel = new PrefLabel(lang(attributes, scope).get)
              activePrefLabel = Some(prefLabel)
              c.prefLabels += prefLabel
            })
          //            println(s"Pref Label lang ${lang(attributes, scope)}")

          case EvElemEnd("skos", "prefLabel") =>
            activePrefLabel.map(d => d.text = textBuilder.toString())
            textBuilder.clear()
          //            println(s"finished PrefLabel $activePrefLabel")


          case EvElemStart("skos", "altLabel", attributes, scope) =>
            activeConcept.map(c => {
              val altLabel: AltLabel = new AltLabel(lang(attributes, scope).get)
              activeAltLabel = Some(altLabel)
              c.altLabels += altLabel
            })
          //            println(s"Alt Label lang ${lang(attributes, scope)}")

          case EvElemEnd("skos", "altLabel") =>
            activeAltLabel.map(d => d.text = textBuilder.toString())
            textBuilder.clear()
          //            println(s"finished AltLabel $activeAltLabel")


          case EvElemStart("skos", "inScheme", attributes, scope) =>
            val r = resource(attributes, scope)
            activeConceptScheme.map(s => if (s.about != r.get) throw new RuntimeException)
          //            println(s"In Scheme resource ${resource(attributes, scope)}")

          case EvElemEnd("skos", "inScheme") =>
            textBuilder.clear()
          //            println(s"finished InScheme $activeConceptScheme")

          case EvElemStart("skos", "broader", attributes, scope) =>
            val r = resource(attributes, scope)
            val target = concepts.get(r.get)
            activeConcept.map { c =>
              target.map(t => c.broaderConcepts += t)
            }
          //            println(s"Broader resource ${resource(attributes, scope)}")

          case EvElemEnd("skos", "broader") =>
            textBuilder.clear()
          //            println(s"finished Broader")


          case EvElemStart("skos", "narrower", attributes, scope) =>
            val r = resource(attributes, scope)
            val target = concepts.get(r.get)
            activeConcept.map { c =>
              target.map(t => c.narrowerConcepts += t)
            }
          //            println(s"Narrower resource ${resource(attributes, scope)}")

          case EvElemEnd("skos", "narrower") =>
            textBuilder.clear()
          //            println(s"finished Narrower")

          case EvElemStart(pre, label, attributes, scope) =>
            println(s"!!!START $label")
            attributes.foreach {
              attr =>
                println(s"Attribute $attr")
            }

          case EvElemEnd(pre, label) =>
            println(s"!!!END $label")

          case EvText(text) =>
            val crunched = FileHandling.crunchWhitespace(text)
            if (!crunched.isEmpty) {
              textBuilder.append(crunched)
              //              println(s"Text '$crunched'")
            }

          case EvEntityRef(entity) =>
          //            println(s"Entity $entity")

          case EvComment(text) =>
          //            println(s"Comment $text")
          //            FileHandling.stupidParser(text, string => node.value(FileHandling.translateEntity(string)))

          case x =>
            println("EVENT? " + x)
        }
      }

      println(s"$activeConceptScheme")
    }
  }

}
