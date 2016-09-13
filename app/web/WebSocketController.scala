package web

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.event.LoggingReceive
import akka.stream.Materializer
import play.api.Logger
import play.api.cache.CacheApi
import play.api.libs.streams.ActorFlow
import play.api.mvc.WebSocket
import play.api.mvc.Controller
import web.WebSocketController.DatasetSocketActor

object WebSocketController {
  class DatasetSocketActor(out: ActorRef) extends Actor {
    def receive = LoggingReceive {
      case politeMessage: String =>
        Logger.info(s"WebSocket: $politeMessage")
    }
  }

}


class WebSocketController(val cacheApi: CacheApi, implicit val actorSystem: ActorSystem,
                          implicit val materializer: Materializer) extends Controller with Security {

  def dataset: WebSocket = WebSocket.accept[String, String] { request =>
    ActorFlow.actorRef( out => Props(new DatasetSocketActor(out)) )
  }

}
