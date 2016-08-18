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
package mapping

import akka.actor.{Actor, ActorLogging, Props}
import dataset.DatasetActor.WorkFailure
import dataset.DsInfo
import org.OrgContext
import org.OrgContext._
import services.ProgressReporter
import services.ProgressReporter.ProgressState
import services.StringHandling.slugify
import triplestore.Sparql._

object Skosifier {

  val chunkSize = 12

  case class SkosificationComplete(skosifiedField: SkosifiedField)

  def props(dsInfo: DsInfo, orgContext: OrgContext) = Props(new Skosifier(dsInfo, orgContext))

  case class SkosificationJob(skosifiedField: SkosifiedField, cases: List[SkosificationCase]) {
    val ensureSkosEntries = cases.map(_.ensureSkosEntryQ).mkString
    val changeLiteralsToUris = cases.map(_.literalToUriQ).mkString

    override def toString = s"$skosifiedField: $cases"
  }

}

class Skosifier(dsInfo: DsInfo, orgContext: OrgContext) extends Actor with ActorLogging {

  import context.dispatcher
  import mapping.Skosifier._

  implicit val ts = orgContext.TS

  var progressOpt: Option[ProgressReporter] = None
  var progressCount = 0

  def receive = {

    case skosifiedField: SkosifiedField => actorWork(context) {
      val progressReporter = ProgressReporter(ProgressState.SKOSIFYING, context.parent)
      ts.query(countSkosificationCasesQ(skosifiedField)).map(countFromResult).map { count =>
        progressReporter.setMaximum(count)
        log.info(s"Set progress maximum to $count")
        progressOpt = Some(progressReporter)
        ts.query(listSkosificationCasesQ(skosifiedField, Skosifier.chunkSize)).map { listCasesResult =>
          self ! SkosificationJob(skosifiedField, createCasesFromQueryValues(skosifiedField, listCasesResult))
        } onFailure {
          case e: Throwable => context.parent ! WorkFailure("Problem listing cases", Some(e))
        }
      } onFailure {
        case e: Throwable => context.parent ! WorkFailure("Problem counting cases", Some(e))
      }
    }

    case job: SkosificationJob => actorWork(context) {
      log.info(s"Cases: ${job.cases.map(c => slugify(c.literalValueText))}")
      ts.up.sparqlUpdate(job.ensureSkosEntries + job.changeLiteralsToUris).map { ok =>
        progressCount += job.cases.size
        progressOpt.get.sendValue(Some(progressCount))
        if (job.cases.size == chunkSize) {
          ts.query(listSkosificationCasesQ(job.skosifiedField, chunkSize)).map { listCasesResult =>
            if (listCasesResult.nonEmpty) {
              val skosCases: List[SkosificationCase] = createCasesFromQueryValues(job.skosifiedField, listCasesResult)
//              log.info(s"Next cases: ${skosCases.map(c => c.literalValueText)}")
              if (skosCases.headOption == job.cases.headOption) {
                throw new RuntimeException(s"Done ${skosCases.headOption} already!")
              }
              self ! SkosificationJob(job.skosifiedField, skosCases)
            }
            else {
              context.parent ! SkosificationComplete(job.skosifiedField)
            }
          } onFailure {
            case e: Exception => context.parent ! WorkFailure("Problem listing cases again", Some(e))
          }
        }
        else {
          context.parent ! SkosificationComplete(job.skosifiedField)
        }
      } onFailure {
        case e: Exception => context.parent ! WorkFailure("Problem with skosify update", Some(e))
      }
    }
  }
}
