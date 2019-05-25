package com.example

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.{KillSwitches, ThrottleMode}
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

  // required by the `ask` (?) method below
  private implicit lazy val timeout: Timeout = Timeout(5.seconds) // usually we'd obtain the timeout from the system's configuration

  import scala.concurrent.duration._

  /*
   | streams are re-usable so we can define it here and use it for every request
   */
  // (2)
//    private val numbers = Source.tick(0 millis, 10 millis, 1)
  // (3)
  // get the list of messages for each time and flat-map them into individual messages
  private val numNeurons = 10
  private val sharedKillSwitch = KillSwitches.shared("my-kill-switch")
  private val throttled = Source(1 to 1000)
    .throttle(4, 20.millis, 0, ThrottleMode.Shaping)
    .map(_ => messages(numNeurons))
    .mapConcat(identity)
    .via(sharedKillSwitch.flow)

  //    .via(KillSwitches.single)(Keep.both)
  // (1)
  //  private val throttled = Source.repeat(NotUsed).map(_ => 1).throttle(1, 5 millis)

  // unused
  //  private val incoming = Sink.(message => println(message))

  private var startTime = System.currentTimeMillis()

  /**
    * get a random number of messages (i.e. some random set of neurons spike)
    * @param numNeurons The number of neurons for which signals are sent
    * @return
    */
  private def messages(numNeurons: Int): List[TextMessage] = {
    val fireTime = System.currentTimeMillis() - startTime
    println(fireTime)
    val neuronsFiring = Random.nextInt(numNeurons)
    Random
      .shuffle(Range(0, numNeurons).toList)
      .take(neuronsFiring)
      .map(index => TextMessage(s"out-$index,$fireTime,1"))
  }

  // todo need to use a graph to split the messages based on what time of neuron event occurred.
  //    1. there is mention of lazily create sources and turning them on when required. So for example,
  //       if UI client wants spikes, then send those, if the UI client wants weights, then send those.
  //    2. Or would it make sense to send all the data and have the client parse?
  //    3. Or would it be best to have the client state what it wants, and then send only those types of
  //       data? For example, just send weight and spikes.
  /**
    * @return The greeter that sends messages (neuron firing messages) down the websocket
    */
  def greeter: Flow[Message, Message, Any] = {
    println("starting web socket")
    Flow.fromSinkAndSourceCoupled(
      // incoming messages from the websocket
      Sink.foreach(message => message.asTextMessage.getStrictText match {
        case "stop" =>
          println(s"client requested stop")
//          sharedKillSwitch.shutdown()

        case other => println(s"from client: $other")
      }),

      // outgoing messages from the websocket
      Source.combine(
        Source.single(TextMessage(s"desc: neurons=$numNeurons")),   // initial network description
        throttled   // all the messages that are being received
      )(Concat(_))
    )
  }

  // the route
  lazy val webSocketRoutes: Route =
    path("web-socket") {
      get {
        startTime = System.currentTimeMillis()
        handleWebSocketMessages(greeter)
      }
    }
}
