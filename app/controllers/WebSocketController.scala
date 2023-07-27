package controllers

import javax.inject._
import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext
import play.api._
import play.api.mvc._
import play.api.libs.json.Json
import play.api.libs.streams.ActorFlow
import play.api.mvc.{BaseController, WebSocket}
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.stream.Materializer
import akka.util.Timeout

import dataset.DatasetActor.Active
import dataset.DsInfo

@Singleton
class WebSocketController @Inject() (implicit
    actorSystem: ActorSystem,
    mat: Materializer,
    ec: ExecutionContext
) extends InjectedController {

  implicit val timeout: Timeout = Timeout(500, TimeUnit.MILLISECONDS)

  object DatasetSocketActor {
    def props(out: ActorRef) = Props(new DatasetSocketActor(out))
  }

  class DatasetSocketActor(val out: ActorRef) extends Actor with ActorLogging {

    def receive = {
      case active: Active => out ! Json.stringify(Json.toJson(active))
      case idle: DsInfo => out ! Json.stringify(Json.toJson(idle))
      case fromClient: String => log.debug(s"Message from browser: $fromClient")
    }
  }

  def dataset: WebSocket = WebSocket.accept[String, String] { request =>
    ActorFlow.actorRef(out => DatasetSocketActor.props(out))
  }

}
