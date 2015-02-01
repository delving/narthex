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

import scala.concurrent.ExecutionContext.Implicits.global

object Skosifier extends Skosification {

  case object ScanForWork

  def props(ts: TripleStore) = Props(new Skosifier(ts))

}

class Skosifier(ts: TripleStore) extends Actor with ActorLogging with Skosification {

  import mapping.Skosifier._

  val chunkSize = 12
  var fieldsBusy = Set.empty[SkosifiedField]

  def receive = {

    /*
        scan for fields
        list literal values for each
        discard the fields which have no literal values
        skosify remaining fields until no liter
     */


    case ScanForWork =>
      ts.query(listSkosifiedFields).onSuccess {
        case fieldList =>
          val (busy, idle) = fieldList.map(SkosifiedField(_)).partition(sf => fieldsBusy.contains(sf))
          idle.map { sf =>
            ts.query(listSkosificationCases(sf, chunkSize)).onSuccess {
              case valueResult =>
                if (valueResult.nonEmpty) {
                  val cases = createCases(sf, valueResult)
                  fieldsBusy += sf
                }
                else {
                  fieldsBusy -= sf
                }
            }
          }
      }

    //    case caseList: List[SkosificationCase] =>
    //      val changeLiterals = caseList.map(_.changeLiteralToUri).mkString("\n")
    //      ts.update(changeLiterals).map { errorOpt =>
    //        println(s"changeLiterals : error=$errorOpt")
    //      }
  }
}
