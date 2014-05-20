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

import scala.xml.pull._
import scala.io.Source
import scala.xml.pull.EvElemStart
import scala.xml.pull.EvText
import scala.xml.pull.EvElemEnd
import scala.collection.mutable
import play.Logger

trait RecordHandling {

  class RecordParser(val recordRoot: String, val uniqueId: String) {

    def parse(source: Source, totalRecords: Int, progress: Int => Unit) = {
      val events = new XMLEventReader(source)
      var percentWas = -1
      var lastProgress = 0l
      var recordCount = 0L
      var withinRecord = false

      def sendProgress(): Unit = {
        val percent = ((recordCount * 100) / totalRecords).toInt
        if (percent > percentWas && (System.currentTimeMillis() - lastProgress) > 333) {
          progress(percent)
          percentWas = percent
          lastProgress = System.currentTimeMillis()
        }
      }

      def push(tag: String) = {
        path.push(tag)
        val string = pathString
        if (withinRecord) {
          if (string == uniqueId) {
            Logger.info(s"unique=$string")
          }
        }
        else if (string == recordRoot) {
          withinRecord = true
          Logger.info(s"start=$string")
        }
      }

      def pop(tag: String) = {
        val string = pathString
        path.pop()
        if (withinRecord && string == recordRoot) {
          withinRecord = false
          recordCount += 1
          sendProgress()
          Logger.info(s"end=$string")
        }
      }

      while (events.hasNext) {

        events.next() match {

          case EvElemStart(pre, label, attrs, scope) =>
            push(FileHandling.tag(pre, label))
            attrs.foreach {
              attr =>
                val tag = s"@${attr.prefixedKey}"
                push(tag)
                // todo: something
                pop(tag)
            }

          case EvText(text) =>

          case EvEntityRef(entity) =>

          case EvElemEnd(pre, label) => pop(FileHandling.tag(pre, label))

          case EvComment(text) =>

          case x => println("EVENT? " + x) // todo: record these in an error file for later
        }
      }

      progress(100)
    }

    val path = new mutable.Stack[String]

    def pathString = "/" + path.reverse.mkString("/")

    def showPath() = Logger.info(pathString)

  }

}
