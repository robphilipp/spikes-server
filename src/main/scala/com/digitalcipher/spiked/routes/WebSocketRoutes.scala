package com.digitalcipher.spiked.routes

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.Timeout
import com.digitalcipher.spiked
import com.digitalcipher.spiked.NetworkCommander
import com.digitalcipher.spiked.NetworkCommanderManager.RetrieveNetworkById

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Web socket route for streaming data to the spikes UI
  */
class WebSocketRoutes(webSocketPath: String, networkManager: ActorRef, actorSystem: ActorSystem) {
  private implicit val timeout: Timeout = Timeout(1 seconds)

  lazy val webSocketRoutes: Route = pathPrefix(webSocketPath / """[a-zA-Z0-9\-_]*""".r) { id =>
    println(s"id: $id")
    handleWebSocketMessages(networkEventHandler(id))
  }

  /**
    * @param networkCommanderId The ID of the network commander used to control the spikes network
    * @return creates a new network route as a flow that sends incoming messages to the spiked-network
    *         actor and routes messages form the spiked-network actor to the web-socket client.
    */
  def networkEventHandler(networkCommanderId: String): Flow[Message, Message, NotUsed] = {
    val response = Await
      .result(networkManager.ask(RetrieveNetworkById(networkCommanderId)), timeout.duration)
      .asInstanceOf[Either[String, ActorRef]]
    if(response.isLeft) {
      throw new IllegalArgumentException(s"network commander not found; network commander ID: $networkCommanderId")
    }
    val networkActor = response.toOption.get

    // messages coming from the web-socket client
    val incomingMessages: Sink[Message, NotUsed] =
      Flow[Message]
        .map {
          case TextMessage.Strict(text) => NetworkCommander.IncomingMessage(text)
          case _ => // do nothing
        }
        .to(Sink.actorRef(networkActor, PoisonPill))

    // messages that go out to the web-socket client
    val outgoingMessages: Source[Message, NotUsed] =
      Source
        .actorRef[spiked.NetworkCommander.OutgoingMessage](10, OverflowStrategy.fail)
        .mapMaterializedValue { outgoingMessageActor =>
          // you need to send a Build message to get the actor in a state
          // where it's ready to receive and send messages, we used the mapMaterialized value
          // so we can get access to it as soon as this is materialized
          networkActor ! NetworkCommander.Build(outgoingMessageActor)
          NotUsed
        }
        .map {
          // Domain Model => WebSocket Message
          case NetworkCommander.OutgoingMessage(text) => TextMessage(text)
        }

    Flow.fromSinkAndSource(incomingMessages, outgoingMessages)
  }
}

object WebSocketRoutes {
  def apply(webSocketPath: String, networkManager: ActorRef, actorSystem: ActorSystem) =
    new WebSocketRoutes(webSocketPath, networkManager, actorSystem)
}
