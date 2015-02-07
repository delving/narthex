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
import triplestore.TripleStore
import triplestore.TripleStore.QueryValue

import scala.concurrent.ExecutionContext.Implicits.global

object Skosifier {

  case object ScanForWork

  def props(ts: TripleStore) = Props(new Skosifier(ts))

}

class Skosifier(ts: TripleStore) extends Actor with ActorLogging with Skosification {

  import mapping.Skosifier._

  val chunkSize = 25

  case class SkosificationJob(sf: SkosifiedField, scResult: List[Map[String, QueryValue]]) {
    val cases = createCases(sf, scResult)
    val ensureSkosEntries = cases.map(_.ensureSkosEntry).mkString
    val changeLiteralsToUris = cases.map(_.changeLiteralToUri).mkString
    override def toString = sf.toString
  }

  def receive = {

    case ScanForWork =>
      ts.query(listSkosifiedFields).map { sfResult =>
        sfResult.map(SkosifiedField(_)).map { sf =>
          val casesExist = skosificationCasesExist(sf)
          ts.ask(casesExist).map(exists => if (exists) {
            log.info(s"Job for $sf")
            ts.query(listSkosificationCases(sf, chunkSize)).map(self ! SkosificationJob(sf, _))
          })
        }
      }

    case job: SkosificationJob =>
      log.info(s"Doing $job")
      ts.update(job.ensureSkosEntries + job.changeLiteralsToUris).map { ok =>
        if (job.cases.size == chunkSize) {
          ts.query(listSkosificationCases(job.sf, chunkSize)).map { scResult =>
            if (scResult.nonEmpty) {
              log.info(s"Another job for $job")
              self ! SkosificationJob(job.sf, scResult)
            }
          }
        }
      }

  }
}
