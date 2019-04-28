package com.example

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.event.Logging
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage, UpgradeToWebSocket}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.util.{ByteString, Timeout}

import scala.concurrent.duration._
import scala.util.Random

trait WebSocketRoutes extends JsonSupport {
  // we leave these abstract, since they will be provided by the App
  implicit def system: ActorSystem

  private lazy val log = Logging(system, classOf[RandomRoutes])

  // other dependencies that UserRoutes use
  //  def userRegistryActor: ActorRef

  // Required by the `ask` (?) method below
  private implicit lazy val timeout: Timeout = Timeout(5.seconds) // usually we'd obtain the timeout from the system's configuration

  // streams are re-usable so we can define it here
  // and use it for every request
//  private val numbers = Source.fromIterator(() => Iterator.continually(Random.nextInt()))
  import scala.concurrent.duration._
  private val numbers = Source.tick(0 millis, 10 millis, 1)
//  fromIterator(() => {
//    Thread.sleep(10)
//    Iterator.fill(100000)(Random.nextDouble() * 1000)
//  })

  def greeter: Flow[Message, Message, Any] = Flow.fromSinkAndSource(Sink.ignore, numbers.map(_ => TextMessage(s"${Random.nextDouble() * 1000}")))

  lazy val webSocketRoutes: Route =
    path("web-socket") {
      get {
        handleWebSocketMessages(greeter)
      }
    }
}
