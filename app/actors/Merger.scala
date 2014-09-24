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

package actors

import java.io.{BufferedReader, File, FileReader, FileWriter}

import actors.Merger._
import actors.Sorter._
import akka.actor.{Actor, Props}
import org.apache.commons.io.FileUtils
import play.api.Logger
import services.NodeRepo

/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

object Merger {

  case class Merge(inFileA: File, inFileB: File, mergeResultFile: File, sortType: SortType)

  case class Merged(merge: Merge, fileA: File, sortType: SortType)

  def props(nodeRepo: NodeRepo) = Props(new Merger(nodeRepo))
}

class Merger(val nodeRepo: NodeRepo) extends Actor {
  val log = Logger

  def receive = {

    case Merge(inFileA, inFileB, mergeResultFile, sortType) =>
      log.debug(s"Merge : ${inFileA.getName} and ${inFileB.getName}")
      val inputA = new BufferedReader(new FileReader(inFileA))
      val inputB = new BufferedReader(new FileReader(inFileB))

      def lineOption(reader: BufferedReader) = {
        val string = reader.readLine()
        if (string != null) Some(string) else None
      }

      val outputFile = nodeRepo.tempSort
      val output = new FileWriter(outputFile)

      def write(line: Option[String]) = {
        output.write(line.get)
        output.write("\n")
      }

      var lineA: Option[String] = lineOption(inputA)
      var lineB: Option[String] = lineOption(inputB)
      while (lineA.isDefined || lineB.isDefined) {
        if (lineA.isDefined && lineB.isDefined) {
          val comparison = sortType.ordering.compare(lineA.get, lineB.get)
          if (comparison < 0) {
            write(lineA)
            lineA = lineOption(inputA)
          }
          else {
            write(lineB)
            lineB = lineOption(inputB)
          }
        }
        else if (lineA.isDefined) {
          write(lineA)
          lineA = lineOption(inputA)
        }
        else if (lineB.isDefined) {
          write(lineB)
          lineB = lineOption(inputB)
        }
      }
      output.close()
      inputA.close()
      inputB.close()
      FileUtils.deleteQuietly(inFileA)
      FileUtils.deleteQuietly(inFileB)
      sender ! Merged(Merge(inFileA, inFileB, mergeResultFile, sortType), outputFile, sortType)
  }
}
