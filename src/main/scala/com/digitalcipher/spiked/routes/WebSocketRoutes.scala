package com.digitalcipher.spiked.routes

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.digitalcipher.spiked
import com.digitalcipher.spiked.{NetworkCommanderManager, NetworkCommander}
import com.typesafe.config.ConfigFactory

/**
  * Web socket route for streaming data to the spikes UI
  */
trait WebSocketRoutes {
  // read the configuration
  private val config = ConfigFactory.parseResources("application.conf")
  val webSocketPath: String = Option(config.getString("http.webSocketPath")).getOrElse("")

  implicit def actorSystem: ActorSystem

  lazy val networkManager: ActorRef = actorSystem.actorOf(Props[NetworkCommanderManager], "spikes-network-manager")
  lazy val webSocketRoutes: Route = networkRoute

  /**
    * @return The network route
    */
  def networkRoute: Route = pathPrefix(webSocketPath / """[a-zA-Z0-9\-_]*""".r) { id =>
    println(s"id: $id")
    handleWebSocketMessages(networkEventHandler(id))
  }

  /**
    * @param networkCommanderId The ID of the network commander used to control the spikes network
    * @return creates a new network route as a flow that sends incoming messages to the spiked-network
    *         actor and routes messages form the spiked-network actor to the web-socket client.
    */
  def networkEventHandler(networkCommanderId: String): Flow[Message, Message, NotUsed] = {
    // create a network actor for the webSocket connection that knows about its network manager
    val networkActor = actorSystem.actorOf(NetworkCommander.props(networkCommanderId, networkManager))

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
