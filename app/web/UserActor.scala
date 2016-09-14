package web

import akka.actor._
import akka.event.LoggingReceive
import dataset.DatasetActor.Active
import dataset.DsInfo
import play.api.libs.json._
import web.DatasetUpdateMonitorActor.AddWatcher

class UserActor(out: ActorRef, datasetUpdateMonitorActorRef: ActorRef) extends Actor with ActorLogging {

  override def receive: Receive = LoggingReceive {
    case idleInfo: DsInfo =>
      out ! Json.toJson(idleInfo)
    case progressInfo: Active =>
      out ! Json.toJson(progressInfo)
  }
}

class UserParentActor (datasetUpdateMonitorActorRef: ActorRef)
  extends Actor with ActorLogging {

  import UserParentActor._

  override def receive: Receive = LoggingReceive {
    case Create(id, out) =>
      val child: ActorRef = context.actorOf(UserActor.props(datasetUpdateMonitorActorRef, out), s"userActor-$id")
      sender() ! child
  }
}

object UserParentActor {
  case class Create(id: String, out: ActorRef)
}

object UserActor {

  def props(datasetUpdateMonitorActorRef: ActorRef, out: ActorRef) =
    Props(classOf[UserActor], datasetUpdateMonitorActorRef, out)
}
