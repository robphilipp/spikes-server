package com.example

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.{KillSwitches, SharedKillSwitch, ThrottleMode}
import akka.stream.scaladsl._
import akka.util.Timeout

import scala.concurrent.Future
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
  // get the list of messages for each time and flat-map them into individual messages
  private val numNeurons = 10

  // text message holding the network description
  private val networkDescription: Source[TextMessage, NotUsed] = Source
    .single(TextMessage(s"desc: neurons=$numNeurons"))

  // source for throttled random messages
  private val throttled: Source[TextMessage, NotUsed] = Source(1 to 1000)
    .throttle(4, 20.millis, 0, ThrottleMode.Shaping)
    .mapConcat(_ => messages(numNeurons))

  // combined source that sends the network description first, and then sends the random messages
  private val messageSource: Source[TextMessage, NotUsed] = networkDescription.concat(throttled)

  /**
    * get a random number of messages (i.e. some random set of neurons spike)
    *
    * @param numNeurons The number of neurons for which signals are sent
    * @return a list of text-messages
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

  /**
    * flow that handles requests from the websocket client.
    * @return
    */
  private def clientRequests: Sink[Message, NotUsed] =
    Flow[Message]
      .to(Sink.foreach(message => message.asTextMessage.getStrictText match {
        case "stop" =>
          println(s"client requested stop")

        case other => println(s"from client: $other")
      }))

  private var startTime = System.currentTimeMillis()

//  // source for throttled random messages
//  private val stopSending = KillSwitches.shared("stop-sending")
//  private val throttledStrings: Source[String, NotUsed] = Source(1 to 100)
//    .throttle(4, 20.millis, 0, ThrottleMode.Shaping)
//    .mapConcat(_ => stringMessages(numNeurons))
//    .via(stopSending.flow)
//
//  private def stringMessages(numNeurons: Int): List[String] = {
//    val fireTime = System.currentTimeMillis() - startTime
//    println(fireTime)
//    val neuronsFiring = Random.nextInt(numNeurons)
//    Random
//      .shuffle(Range(0, numNeurons).toList)
//      .take(neuronsFiring)
//      .map(index => s"out-$index,$fireTime,1")
//  }
//
//  private val interactiveFlow: Flow[Message, Message, NotUsed] = Flow[Message]
//    .map {
//      case TextMessage.Strict(msg) => msg match {
//        case "build" =>
//          println("build network request")
//          TextMessage(Source.single(s"desc: neurons=$numNeurons"))
//
//        case "stop" =>
//          println("client stop request")
//          stopSending.shutdown()
//          TextMessage(Source.single("stopped"))
//
//        case other => TextMessage(Source.single(s"Invalid request: $other"))
//      }
//
//      case msg =>
//        println("stream")
//        msg
//    }
//    .map {
//      case msg: TextMessage => msg.asTextMessage.getStrictText match {
//        case "build" =>
//          println("build network request")
//          TextMessage(Source.single(s"desc: neurons=$numNeurons"))
//
//        case "stop" =>
//          println("client stop request")
//          stopSending.shutdown()
//          TextMessage(Source.single("stopped"))
//
//        case other => TextMessage(Source.single(s"Invalid request: $other"))
//      }
//    }

//  private val throttledFlow: Flow[Message, Message, NotUsed] = Flow[Message]
//    .map {
//      case TextMessage.Strict(msg) => msg match {
//        case "start" =>
//          println("start")
//          TextMessage(throttledStrings)
//
//        case other => TextMessage(Source.single(s"Invalid request: $other"))
//      }
//
//      case msg =>
//        println("stream")
//        msg
//    }

//  private val messageFlow = Flow[Message].via(interactiveFlow.async).via(throttledFlow.async)

//  private val myFlow: Flow[Message, Message, NotUsed] = Flow[Message]
//    .map(message => message.asTextMessage.getStrictText match {
//      case "build" =>
//        println("build network request")
//        TextMessage(Source.single(s"desc: neurons=$numNeurons"))
//
//      case "start" =>
//        println("start")
//        TextMessage(throttledStrings)
//
//      case "stop" =>
//        println("client stop request")
//        stopSending.shutdown()
//        TextMessage(Source.single("stopped"))
//
//      case other => TextMessage(Source.single(s"Invalid request: $other"))
//    }).async

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
    Flow.fromSinkAndSourceCoupled(clientRequests, messageSource)
//    messageFlow
  }

  // todo 1. add regular REST route to build the network based on the description and return the
  //        actor ref to the network (and possibly the environment?)
  //      2. using the actor ref from the constructed network, we socket call to start the network
  //        and stream back the results.
  //      3. closing the websocket needs to stop the network
  // the route
  lazy val webSocketRoutes: Route =
  path("web-socket") {
    get {
      startTime = System.currentTimeMillis()
      handleWebSocketMessages(greeter)
    }
  }
}
