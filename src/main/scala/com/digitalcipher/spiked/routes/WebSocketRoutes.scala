package com.digitalcipher.spiked.routes

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives.{handleWebSocketMessages, path}
import akka.http.scaladsl.server.Route
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.digitalcipher.spiked
import com.digitalcipher.spiked.json.JsonSupport
import com.digitalcipher.spiked.{NetworkManager, SpikedNetwork}
import com.typesafe.config.ConfigFactory

/**
  * Web socket route for streaming data to the spikes UI
  */
trait WebSocketRoutes {
  // read the configuration
  private val config = ConfigFactory.parseResources("application.conf")
  val webSocketPath: String = Option(config.getString("http.webSocketPath")).getOrElse("")

  implicit def actorSystem: ActorSystem

  lazy val networkManager: ActorRef = actorSystem.actorOf(Props[NetworkManager], "spikes-network-manager")
  lazy val webSocketRoutes: Route = newNetworkRoute

  def newNetwork(): Flow[Message, Message, NotUsed] = {
    // create a network actor for the webSocket connection that knows about its network manager
    val networkActor = actorSystem.actorOf(SpikedNetwork.props("first", networkManager))

    // messages coming from the web-socket client
    val incomingMessages: Sink[Message, NotUsed] =
      Flow[Message]
        .map {
          case TextMessage.Strict(text) => SpikedNetwork.IncomingMessage(text)
          case _ => // do nothing
        }
        .to(Sink.actorRef(networkActor, PoisonPill))

    val outgoingMessages: Source[Message, NotUsed] =
      Source
        .actorRef[spiked.SpikedNetwork.OutgoingMessage](10, OverflowStrategy.fail)
        .mapMaterializedValue { outgoingActor =>
          // you need to send a Build message to get the actor in a state
          // where it's ready to receive and send messages, we used the mapMaterialized value
          // so we can get access to it as soon as this is materialized
          networkActor ! SpikedNetwork.Build(outgoingActor)
          NotUsed
        }
        .map {
          // Domain Model => WebSocket Message
          case SpikedNetwork.OutgoingMessage(text) => TextMessage(text)
        }

    Flow.fromSinkAndSource(incomingMessages, outgoingMessages)
  }

  def newNetworkRoute: Route =
    path(webSocketPath) {
      handleWebSocketMessages(newNetwork())
    }
}
