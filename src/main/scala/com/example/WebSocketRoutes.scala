package com.example

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl._
import akka.util.Timeout

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
  import scala.concurrent.duration._
  private val numbers = Source.tick(0 millis, 10 millis, 1)
//  private val incoming = Sink.(message => println(message))

  private var startTime = System.currentTimeMillis()
  // todo add initialization messages that give the number of neurons; or should there be a REST call
  //    to get the neural network information to allow the user to set up before starting
  def greeter: Flow[Message, Message, Any] = Flow.fromSinkAndSource(
    Sink.ignore,
//    incoming,
//    numbers.map(_ => TextMessage(s"${System.currentTimeMillis() - startTime},${Random.nextDouble() * 1000}"))
    numbers.map(_ => TextMessage(s"out-${Random.nextInt(10)},${System.currentTimeMillis() - startTime},1"))
  )

  lazy val webSocketRoutes: Route =
    path("web-socket") {
      get {
        startTime = System.currentTimeMillis()
        handleWebSocketMessages(greeter)
      }
    }
}
