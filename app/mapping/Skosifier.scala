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

object Skosifier {

  case object WakeupCall

  def props(ts: TripleStore) = Props(new Skosifier(ts))

}

class Skosifier(ts: TripleStore) extends Actor with ActorLogging with Skosification {

  import mapping.Skosifier._

  val chunkSize = 12

  def receive = {

    case WakeupCall =>
//      ts.query(checkForWork(chunkSize)).map(SkosificationCase(_)).foreach(self ! _)

    case s: SkosificationCase =>
      ts.query(s.checkExistence).map { answer =>
        println(s"check existence: $answer")
      }
  }
}
