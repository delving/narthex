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

import scala.collection.mutable
import scala.io.Source
import scala.xml.pull._
import scala.xml.{MetaData, NamespaceBinding}
import play.api.Logger
import mapping.CategoriesSpreadsheet.CategoryCount
import record.CategoryParser.{CategoryMapping, Counter, NULL}
import services.StringHandling._
import services.ProgressReporter

object CategoryParser {

  val NULL = "NULL"

  case class CategoryMapping(source: String, categories: Seq[String])

  case class Counter(var count: Int)

}

class CategoryParser(pathPrefix: String, recordRootPath: String, uniqueIdPath: String, recordContainer: Option[String] = None, categoryMappings: Map[String, CategoryMapping]) {

  private val logger = Logger(getClass)

  var countMap = new collection.mutable.HashMap[String, Counter]()
  var percentWas = -1
  var lastProgress = 0L
  var recordCount = 0

  logger.debug(s"category mappings $categoryMappings")

  def increment(key: String): Unit = countMap.getOrElseUpdate(key, new Counter(1)).count += 1

  def output(categories: List[String]) = {
    for (
      a <- categories
    ) yield increment(a)
    increment(s"$NULL")
    for (
      a <- categories;
      b <- categories if b > a
    ) yield increment(s"$a-$b")
    increment(s"$NULL-$NULL")
    for (
      a <- categories;
      b <- categories if b > a;
      c <- categories if c > b
    ) yield increment(s"$a-$b-$c")
    increment(s"$NULL-$NULL-$NULL")
    recordCount += 1
  }

  def parse(source: Source, avoidIds: Set[String], progressReporter: ProgressReporter): Unit = {
    val path = new mutable.Stack[(String, StringBuilder)]
    val events = new XMLEventReader(source)
    var depth = 0
    val recordCategories = new mutable.TreeSet[String]()
    var startTag = false
    var uniqueId: Option[String] = None

    def pathString = path.reverse.map(_._1).mkString("/", "/", "")

    def recordPathString = if (recordContainer.isDefined && recordContainer.get == recordRootPath) {
      pathString.substring(recordRootPath.length)
    }
    else {
      pathString.substring(pathContainer(recordRootPath).length)
    }

    def pathContainer(string: String) = string.substring(0, string.lastIndexOf("/"))

    def generateUri(value: String) = s"$pathPrefix$recordPathString/${urlEncodeValue(value)}"

    def push(tag: String, attrs: MetaData, scope: NamespaceBinding) = {
      def categoriesFromAttrs() = {
        attrs.foreach { attr =>
          path.push((s"@${attr.prefixedKey}", new StringBuilder()))
          path.pop()
        }
      }
      def findUniqueId(attrs: MetaData) = attrs.foreach { attr =>
        path.push((s"@${attr.prefixedKey}", new StringBuilder()))
        if (pathString == uniqueIdPath) uniqueId = Some(attr.value.toString())
        path.pop()
      }
      path.push((tag, new StringBuilder()))
      val string = pathString
      if (depth > 0) {
        depth += 1
        startTag = true
        categoriesFromAttrs()
        findUniqueId(attrs)
      }
      else if (string == recordRootPath) {
        if (recordContainer.isEmpty) {
          depth = 1
          startTag = true
          recordCategories.clear()
          categoriesFromAttrs()
        }
        findUniqueId(attrs)
      }
      else recordContainer.foreach { container =>
        if (pathContainer(string) == container) {
          depth = 1
          recordCategories.clear()
          startTag = true
          categoriesFromAttrs()
        }
      }
    }

    def addFieldText(text: String) = path.headOption.foreach(_._2.append(text))

    def pop(tag: String) = {
      val string = pathString
      val fieldText = path.head._2
      val text = crunchWhitespace(fieldText.toString(), None)
      fieldText.clear()
      if (depth > 0) {
        // deep record means check container instead
        val hitRecordRoot = recordContainer.map(pathContainer(string) == _ && !string.contains('@')).getOrElse(string == recordRootPath)
        if (hitRecordRoot) {
          val categorySet: Option[List[String]] = uniqueId.map { id =>
            if (id.isEmpty) throw new RuntimeException("Empty unique id!")
            if (avoidIds.contains(id)) None else Some(recordCategories.toList)
          } getOrElse {
            throw new RuntimeException("Missing id!")
          }
          categorySet.foreach(output)
          recordCategories.clear()
          depth = 0
        }
        else {
          if (string == uniqueIdPath) uniqueId = Some(text)
          depth -= 1
          if (startTag && text.nonEmpty) {
            val uri = generateUri(text)
            categoryMappings.get(uri).map { mapping =>
//              mapping.categories.foreach { category =>
//                recordCategories += category
//              }
            }
          }
        }
      }
      else if (recordContainer.isDefined && string == uniqueIdPath) {
        // if there is a deep record, we find the unique idea outside of it
        uniqueId = Some(text)
      }
      path.pop()
    }

    while (events.hasNext) {
      progressReporter.sendValue(Some(recordCount))
      events.next() match {
        case EvElemStart(pre, label, attrs, scope) => push(tag(pre, label), attrs, scope)
        case EvText(text) => addFieldText(text)
        case EvEntityRef(entity) => addFieldText(s"&$entity;")
        case EvElemEnd(pre, label) => pop(tag(pre, label))
        case EvComment(text) => stupidParser(text, entity => addFieldText(s"&$entity;"))
        case EvProcInstr(target, text) =>
        case x =>
          logger.error("EVENT? " + x)
      }
    }
  }

  def categoryCounts: List[CategoryCount] = countMap.map(
    count => CategoryCount(count._1, count._2.count, pathPrefix)
  ).toList
}
