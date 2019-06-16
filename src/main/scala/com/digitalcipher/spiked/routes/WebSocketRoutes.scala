package com.digitalcipher.spiked.routes

import java.util.concurrent.TimeoutException

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
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
  private implicit val timeout: Timeout = Timeout(1.seconds)

  /**
    * The error handler invoked when the network-commander-manager actor does not
    * respond within the allowed response duration.
    * @return The exception handler
    */
  implicit def timeoutExceptionHandler: ExceptionHandler = ExceptionHandler {
    case _: TimeoutException => complete(
      HttpResponse(
        StatusCodes.InternalServerError,
        entity = s"Failed to retrieve network-commander actor for specified ID"
      ))
  }

  /**
    * If the specified network-commander ID is found, then constructs the web-socket route based
    * on a flow from the incoming messages to the network commander actor to the source of the
    * outgoing messages. Otherwise, constructs a flow in which the incoming messages are ignored,
    * and the source sends back only one message--the message associated with the error when
    * attempting to find the network commander.
    */
  lazy val webSocketRoutes: Route = pathPrefix(webSocketPath / """[a-zA-Z0-9\-_]*""".r) { id =>
    Await
      // look up the network commander based on the specified ID
      .result(networkManager.ask(RetrieveNetworkById(id)), timeout.duration)
      .asInstanceOf[Either[String, ActorRef]] match {
        // found network commander, so construct the web socket route using with the network commander
        case Right(actorRef: ActorRef) => handleWebSocketMessages(networkEventHandler(actorRef))

        // network commander wasn't found, so return a flow with a single error message
        case Left(message) => handleWebSocketMessages(Flow.fromSinkAndSource(
          Flow[Message].to(Sink.ignore),
          Source.single(TextMessage(message))
        ))
    }
  }

  /**
    * Creates the flow that is used by the web-socket message handler to deal with incoming messages and
    * send outgoing messages. The flow receives incoming messages (sink), and sends those to the network
    * commander actor. The network commander actor processes the request and sends outgoing messages to the
    * web-socket client.
    *
    * @param networkActor The actor reference of the network commander used to control the spikes network
    * @return A sink-and-source flow where the sink and source are bridged by the network-commander actor
    */
  private def networkEventHandler(networkActor: ActorRef): Flow[Message, Message, NotUsed] = {
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

    // the flow that has a sink for incoming messages, an actor that serves as bridge between
    // the sink and the messages handed to the source (outgoing messages)
    Flow.fromSinkAndSource(incomingMessages, outgoingMessages)
  }
}

object WebSocketRoutes {
  def apply(webSocketPath: String, networkManager: ActorRef, actorSystem: ActorSystem) =
    new WebSocketRoutes(webSocketPath, networkManager, actorSystem)
}
