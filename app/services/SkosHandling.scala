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

trait SkosHandling {

  object SkosVocabulary {
    def apply(source: Source) = {
      val events = new XMLEventReader(source)
      while (events.hasNext) {
        events.next() match {

          case EvElemStart(pre, label, attributes, scope) =>
            println(s"Start $label")
            attributes.foreach {
              attr =>
                println(s"Attribute $attr")
            }

          case EvText(text) =>
            val crunched = FileHandling.crunchWhitespace(text)
            if (!crunched.isEmpty) println(s"Text '$crunched'")

          case EvEntityRef(entity) =>
            println(s"Entity $entity")

          case EvElemEnd(pre, label) =>
            println(s"End $label")

          case EvComment(text) =>
            println(s"Comment $text")
          //            FileHandling.stupidParser(text, string => node.value(FileHandling.translateEntity(string)))

          case x =>
            println("EVENT? " + x) // todo: record these in an error file for later
        }
      }
    }
  }

}
