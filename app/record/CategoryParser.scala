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

import mapping.CategoryDb.CategoryMapping
import play.Logger
import services.{FileHandling, NarthexEventReader, ProgressReporter}

import scala.collection.mutable
import scala.io.Source
import scala.xml.pull._
import scala.xml.{MetaData, NamespaceBinding}

class CategoryParser(recordRootPath: String, uniqueIdPath: String, deepRecordContainer: Option[String] = None, categoryMappings: Map[String, CategoryMapping]) {
  val path = new mutable.Stack[(String, StringBuilder)]
  var percentWas = -1
  var lastProgress = 0l
  var recordCount = 0

  def parse(source: Source, avoidIds: Set[String], output: Set[String] => Unit, progressReporter: ProgressReporter) = {
    val events = new NarthexEventReader(source)
    var depth = 0
    var recordCategories = new mutable.TreeSet[String]()
    var startTag = false
    var uniqueId: Option[String] = None
    var running = true

    def push(tag: String, attrs: MetaData, scope: NamespaceBinding) = {
      def categoriesFromAttrs() = {
        recordCategories.clear()
        startTag = true
        attrs.foreach { attr =>
          val value = "\"" + attr.value.toString() + "\""
          // todo: generate uri for comparison
          val uri = s" ${attr.prefixedKey}=$value"
          categoryMappings.get(uri).map(_.categories.foreach(recordCategories += _))
        }
      }
      path.push((tag, new StringBuilder()))
      val string = pathString
      if (depth > 0) {
        depth += 1
        categoriesFromAttrs()
      }
      else if (string == recordRootPath) {
        if (deepRecordContainer.isEmpty) {
          depth = 1
          categoriesFromAttrs()
        }
      }
      else deepRecordContainer.foreach { recordContainer =>
        if (pathContainer(string) == recordContainer) {
          depth = 1
          categoriesFromAttrs()
        }
      }
    }

    def addFieldText(text: String) = path.headOption.foreach(_._2.append(text))

    def pop(tag: String) = {
      val string = pathString
      val fieldText = path.head._2
      val text = FileHandling.crunchWhitespace(fieldText.toString())
      fieldText.clear()
      path.pop()
      if (depth > 0) {
        // deep record means check container instead
        val hitRecordRoot = deepRecordContainer.map(pathContainer(string) == _).getOrElse(string == recordRootPath)
        if (hitRecordRoot) {
          val categorySet = uniqueId.map { id =>
            if (id.isEmpty) throw new RuntimeException("Empty unique id!")
            if (avoidIds.contains(id)) None else Some(recordCategories.toSet)
          } getOrElse {
            throw new RuntimeException("Missing id!")
          }
          categorySet.foreach { categories =>
            output(categories)
            recordCount += 1
          }
          recordCategories.clear()
          depth = 0
        }
        else {
          if (string == uniqueIdPath) uniqueId = Some(text)
          depth -= 1
          if (startTag) {
            // todo: generate uri for comparison
            val uri = s"generated"
            categoryMappings.get(uri).map(_.categories.foreach(recordCategories += _))
          }
        }
      }
      else if (deepRecordContainer.isDefined && string == uniqueIdPath) {
        // if there is a deep record, we find the unique idea outside of it
        uniqueId = Some(text)
      }
    }

    while (events.hasNext && progressReporter.keepReading(recordCount)) {
      events.next() match {
        case EvElemStart(pre, label, attrs, scope) => push(FileHandling.tag(pre, label), attrs, scope)
        case EvText(text) => addFieldText(text)
        case EvEntityRef(entity) => addFieldText(s"&$entity;")
        case EvElemEnd(pre, label) => pop(FileHandling.tag(pre, label))
        case EvComment(text) => FileHandling.stupidParser(text, entity => addFieldText(s"&$entity;"))
        case x => Logger.error("EVENT? " + x)
      }
    }
    progressReporter.keepWorking
  }

  def pathString = path.reverse.map(_._1).mkString("/", "/", "")

  def pathContainer(string: String) = string.substring(0, string.lastIndexOf("/"))

  def showPath() = Logger.info(pathString)
}
