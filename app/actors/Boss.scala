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

import akka.actor.{ActorLogging, Actor}
import actors.Lingo._
import play.api.libs.json.Json
import actors.Lingo.Analyze
import actors.Lingo.AnalysisProgress
import actors.Lingo.AnalysisError
import actors.Lingo.AnalyzeThese
import services.{Repo, FileHandling}
import org.apache.commons.io.FileUtils

/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

class Boss extends Actor with ActorLogging {

   override def receive: Receive = {

     case AnalyzeThese(jobs) =>
       jobs.foreach {
         job =>
           val analyzer = context.actorOf(Analyzer.props(job._2), job._1.getName)
           analyzer ! Analyze(job._1)
       }

     case AnalysisProgress(fileRepo, count) =>
       Repo.updateJson(fileRepo.status) {
         current => Json.obj("percent" -> count)
       }

     case AnalysisError(fileRepo, file) =>
       log.info(s"File error at ${fileRepo.dir.getName}")
       FileUtils.deleteQuietly(file)
       FileUtils.deleteQuietly(fileRepo.dir)
       context.stop(sender)

     case AnalysisTreeComplete(fileRepo, json, digest) =>
       Repo.updateJson(fileRepo.status) {
         current =>
           Json.obj("index" -> true)
       }
       // todo: rename the original file to include date and digest
       log.info(s"Tree Complete at ${fileRepo.dir.getName}, digest=${FileHandling.hex(digest)}")

     case AnalysisComplete(fileRepo) =>
       log.info(s"Analysis Complete, kill: ${sender.toString()}")
       context.stop(sender)
       Repo.updateJson(fileRepo.status) {
         current =>
           Json.obj("index" -> true, "complete" -> true)
       }
   }
 }
