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
import dataset.DatasetActor.{WebSocketBroadcast, WebSocketProgressBroadcast, WebSocketIdleBroadcast}

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

    override def preStart(): Unit = {
      // Subscribe to the event stream for WebSocket broadcasts
      context.system.eventStream.subscribe(self, classOf[WebSocketBroadcast])
      log.debug("WebSocket actor subscribed to event stream")
    }

    override def postStop(): Unit = {
      // Unsubscribe from the event stream
      context.system.eventStream.unsubscribe(self)
      log.debug("WebSocket actor unsubscribed from event stream")
    }

    def receive = {
      // Handle broadcast wrapper messages from event stream
      case WebSocketProgressBroadcast(active) =>
        out ! Json.stringify(Json.toJson(active))
      case WebSocketIdleBroadcast(dsInfo) =>
        out ! Json.stringify(dsInfo.toSimpleJson)
      // Handle direct messages (legacy support)
      case active: Active => out ! Json.stringify(Json.toJson(active))
      case idle: DsInfo =>
        // Use simple JSON format with expected field names for frontend
        out ! Json.stringify(idle.toSimpleJson)
      case fromClient: String => log.debug(s"Message from browser: $fromClient")
    }
  }

  def dataset: WebSocket = WebSocket.accept[String, String] { request =>
    ActorFlow.actorRef(out => DatasetSocketActor.props(out))
  }

}
