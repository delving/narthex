package mapping

import akka.actor.{Actor, Props}
import dataset.DatasetActor.StartSkosification
import dataset.DsInfo
import mapping.PeriodicSkosifyCheck.ScanForWork
import organization.OrgContext
import play.api.Logger
import triplestore.Sparql._

object PeriodicSkosifyCheck {

  case object ScanForWork

  def props(orgContext: OrgContext) = Props(new PeriodicSkosifyCheck(orgContext))
}

class PeriodicSkosifyCheck(orgContext: OrgContext) extends Actor {

  import context.dispatcher

  implicit val ts = orgContext.ts

  val log = Logger.logger

  def receive = {

    case ScanForWork =>
      ts.query(listSkosifiedFieldsQ).map { sfResult =>
        val allSkosifiedFields = sfResult.map(skosifiedFieldFromResult)
        val perDataset: Map[String, List[SkosifiedField]] = allSkosifiedFields.groupBy(sf => sf.spec)
        perDataset.map { entry =>
          val spec = entry._1
          val skosifiedFields = entry._2
          DsInfo.withDsInfo(spec, orgContext) { dsInfo =>
            skosifiedFields.map { sf =>
              ts.ask(skosificationCasesExistQ(sf)).map(exists => if (exists) {
                orgContext.orgActor ! dsInfo.createMessage(StartSkosification(sf))
              })
            }
          }
        }
      }
  }
}

