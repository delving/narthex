package mapping

import akka.actor.{Actor, Props}
import dataset.DatasetActor.StartSkosification
import dataset.DsInfo
import mapping.PeriodicSkosifyCheck.ScanForWork
import org.OrgActor
import org.OrgContext._
import play.api.Logger
import triplestore.Sparql._

object PeriodicSkosifyCheck {

  case object ScanForWork

  def props() = Props[PeriodicSkosifyCheck]

}

class PeriodicSkosifyCheck extends Actor {

  import context.dispatcher

  val log = Logger.logger

  def receive = {

    case ScanForWork =>
      ts.query(listSkosifiedFieldsQ).map { sfResult =>
        val allSkosifiedFields = sfResult.map(skosifiedFieldFromResult)
        val perDataset: Map[String, List[SkosifiedField]] = allSkosifiedFields.groupBy(sf => sf.spec)
        perDataset.map { entry =>
          val spec = entry._1
          val skosifiedFields = entry._2
          DsInfo.withDsInfo(spec) { dsInfo =>
            skosifiedFields.map { sf =>
              ts.ask(skosificationCasesExistQ(sf)).map(exists => if (exists) {
                log.info(s"Work for $sf: sending StartSkosification")
                OrgActor.actor ! dsInfo.createMessage(StartSkosification(sf))
              })
            }
          }
        }
      }
  }
}

