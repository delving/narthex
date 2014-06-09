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

import akka.actor.{Props, ActorLogging, Actor}
import services.{NodeRepo, Repo, TreeHandling}
import java.io.{FileWriter, File, FileReader, BufferedReader}
import play.api.libs.json.Json
import Collator._

/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

object Collator {

  case class Count()

  case class Counted(nodeRepo: NodeRepo, uniqueCount: Int, sampleFiles: Seq[Int])

  def props(nodeRepo: NodeRepo) = Props(new Collator(nodeRepo))
}

class Collator(val nodeRepo: NodeRepo) extends Actor with ActorLogging with TreeHandling {

   def receive = {

     case Count() =>
       log.debug(s"Count : ${nodeRepo.sorted.getName}")
       val sorted = new BufferedReader(new FileReader(nodeRepo.sorted))

       val samples = nodeRepo.sampleJson.map(pair => (new RandomSample(pair._1), pair._2))

       def createSampleFile(randomSample: RandomSample, sampleFile: File) = {
         Repo.createJson(sampleFile, Json.obj("sample" -> randomSample.values))
       }

       def lineOption = {
         val string = sorted.readLine()
         if (string != null) Some(string) else None
       }

       val counted = new FileWriter(nodeRepo.counted)
       val unique = new FileWriter(nodeRepo.uniqueText)
       var occurrences = 0
       var uniqueCount = 0

       def writeValue(string: String) = {
         counted.write(f"$occurrences%7d $string%s\n")
         unique.write(string)
         unique.write("\n")
         samples.foreach(pair => pair._1.record(string))
         uniqueCount += 1
       }

       var previous: Option[String] = None
       var current = lineOption
       while (current.isDefined) {
         if (current == previous) {
           occurrences += 1
         }
         else {
           previous.foreach(writeValue)
           previous = current
           occurrences = 1
         }
         current = lineOption
       }
       previous.foreach(writeValue)
       sorted.close()
       counted.close()
       unique.close()
       val bigEnoughSamples = samples.filter(pair => uniqueCount > pair._1.size * 2)
       val usefulSamples = if (bigEnoughSamples.isEmpty) List(samples.head) else bigEnoughSamples
       usefulSamples.foreach(pair => createSampleFile(pair._1, pair._2))
       sender ! Counted(nodeRepo, uniqueCount, usefulSamples.map(pair => pair._1.size))
   }
 }
