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
import triplestore.Sparql
import triplestore.Sparql._
import triplestore.TripleStore.QueryValue

object Skosifier {

  val chunkSize = 10

  case class SkosificationComplete(skosifiedField: SkosifiedField)

  def props(dsInfo: DsInfo) = Props(new Skosifier(dsInfo))

  case class SkosificationJob(skosifiedField: SkosifiedField, scResult: List[Map[String, QueryValue]]) {
    val cases = Sparql.createCases(skosifiedField, scResult)
    val ensureSkosEntries = cases.map(_.ensureSkosEntryQ).mkString
    val changeLiteralsToUris = cases.map(_.literalToUriQ).mkString

    override def toString = s"$skosifiedField: $scResult"
  }

}

class Skosifier(dsInfo: DsInfo) extends Actor with ActorLogging {

  import context.dispatcher
  import mapping.Skosifier._

  implicit val ts = OrgContext.TS

  var progressOpt: Option[ProgressReporter] = None
  var progressCount = 0

  var caseDone: Option[SkosificationCase] = None

  def receive = {

    case skosifiedField: SkosifiedField => actorWork(context) {
      val progressReporter = ProgressReporter(ProgressState.SKOSIFYING, context.parent)
      ts.query(countSkosificationCasesQ(skosifiedField)).map(countFromResult).map { count =>
        progressReporter.setMaximum(count)
        log.info(s"Set progress maximum to $count")
        progressOpt = Some(progressReporter)
        ts.query(listSkosificationCasesQ(skosifiedField, Skosifier.chunkSize)).map { scResult =>
          self ! SkosificationJob(skosifiedField, scResult)
        } onFailure {
          case e: Throwable => context.parent ! WorkFailure("Problem listing cases", Some(e))
        }
      } onFailure {
        case e: Throwable => context.parent ! WorkFailure("Problem counting cases", Some(e))
      }
    }

    case job: SkosificationJob => actorWork(context) {
      log.info(s"Cases: ${job.cases.map(c => slugify(c.literalValueText))}")
      caseDone.map(c => if (c == job.cases.head) throw new RuntimeException(s"Done $c already"))
      ts.up.sparqlUpdate(job.ensureSkosEntries + job.changeLiteralsToUris).map { ok =>
        caseDone = job.cases.headOption
        progressCount += job.cases.size
        progressOpt.get.sendValue(Some(progressCount))
        if (job.cases.size == chunkSize) {
          ts.query(Sparql.listSkosificationCasesQ(job.skosifiedField, chunkSize)).map { scResult =>
            if (scResult.nonEmpty) {
              self ! SkosificationJob(job.skosifiedField, scResult)
            }
            else {
              context.parent ! SkosificationComplete(job.skosifiedField)
            }
          } onFailure {
            case e: Exception =>
              context.parent ! WorkFailure(e.toString, Some(e))
          }
        }
        else {
          context.parent ! SkosificationComplete(job.skosifiedField)
        }
      } onFailure {
        case e: Exception =>
          context.parent ! WorkFailure(e.toString, Some(e))
      }
    }
  }
}
