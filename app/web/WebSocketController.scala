package web

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.stream.Materializer
import akka.util.Timeout
import dataset.DatasetActor.Active
import dataset.DsInfo
import play.api.cache.CacheApi
import play.api.libs.json.Json
import play.api.libs.streams.ActorFlow
import play.api.mvc.{Controller, WebSocket}

import scala.concurrent.ExecutionContext

class WebSocketController(val cacheApi: CacheApi, val sessionTimeoutInSeconds: Int)
                         (implicit actorSystem: ActorSystem,
                          mat: Materializer,
                          ec: ExecutionContext)
  extends Controller with Security {

  implicit val timeout = Timeout(500, TimeUnit.MILLISECONDS)

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
