package com.digitalcipher.spiked

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, PoisonPill, Props}
import akka.util.Timeout
import com.digitalcipher.spiked.NetworkCommander._
import com.digitalcipher.spiked.json.JsonSupport._
import spray.json._

import scala.concurrent.ExecutionContextExecutor
import scala.util.Random

class NetworkCommander(id: String, manager: ActorRef) extends Actor with ActorLogging {

  implicit val executionContext: ExecutionContextExecutor = context.dispatcher
  import scala.concurrent.duration._

  implicit val timeout: Timeout = Timeout(1.seconds)

  override def receive: Receive = uninitialized

  /**
    * The initial state of the network actor. Once a connection is opened, transitions to
    * the `waiting(...)` method, which is handed the web-socket actor that serves as the sink
    * to this source
    * @return a receive instance
    */
  def uninitialized: Receive = {
    // builds the network when the connection is established. The wsActor is the web-socket
    // actor to which messages are sent. Recall that the web-socket route has a handler, and
    // the handler returns a flow. The flow is a sink-to-source flow where the sink and source
    // are decoupled, except through this actor. This source sends messages to the web-socket
    // sink, which sends them back to the UI client.
    case Build(outgoingMessageActor) =>
      log.info(s"building network commander; id: $id")
      // todo 1. build the actual spiked network
      //      2. connect to kafka
      //      3. stream topology messages to the outgoing message actor

      // transition to the state where the network is built, but not yet running
      context.become(built(outgoingMessageActor))
  }

  /**
    * State where the network is waiting to be started. At this point the network is already built.
    * @param outgoingMessageActor The web-socket actor passed from the uninitialized state.
    * @return a receive instance
    */
  def built(outgoingMessageActor: ActorRef): Receive = {
    case IncomingMessage(text) => text.parseJson.convertTo match {
      case NetworkCommand("start") =>
        log.info(s"starting network; id: $id")

        // todo ultimately, this will be replaced by a source from the kafka
        //    1. there are the network event (learning, spikes, membrane potential) messages

        // start the time and send messages every interval
        val cancellable = context.system.scheduler.schedule(
          initialDelay = 0.seconds,
          interval = 20.milliseconds,
          receiver = self,
          SendMessage()
        )
        context.become(running(outgoingMessageActor, System.currentTimeMillis(), cancellable))

      case NetworkCommand("destroy") =>
        log.info(s"destroying network; id: $id")
        outgoingMessageActor ! PoisonPill

      case NetworkCommand(command) => log.error(s"Invalid network command (waiting); command: $command")
    }
    case _ => log.error(s"Invalid incoming message type")
  }

  /**
    * In this state, the network is running
    * @param outgoingMessageActor The web-socket actor to which to send the messages
    * @param startTime The start time of the simulation (i.e. when the network transitioned to this state
    * @param cancellable The cancellable for the scheduled (will disappear when data is coming from kafka)
    * @return a receive instance
    */
  def running(outgoingMessageActor: ActorRef, startTime: Long, cancellable: Cancellable): Receive = {
    // todo the number of neurons should not be hard coded; replace this with the flow from kafka
    case SendMessage() => messages(10, startTime).foreach(message => outgoingMessageActor ! message)

    case IncomingMessage(text) => text.parseJson.convertTo match {
      case NetworkCommand("stop") =>
        log.info(s"stopping network; id: $id")
        cancellable.cancel()
        context.become(built(outgoingMessageActor))

      case NetworkCommand(command) => log.error(s"Invalid network command (running); command: $command")
    }

    case message => log.error(s"Invalid message type; message type: ${message.getClass.getName}")
  }

  /**
    * get a random number of messages (i.e. some random set of neurons spike)
    *
    * @param numNeurons The number of neurons for which signals are sent
    * @return a list of text-messages
    */
  private def messages(numNeurons: Int, startTime: Long): List[OutgoingMessage] = {
    val fireTime = System.currentTimeMillis() - startTime
    val neuronsFiring = Random.nextInt(numNeurons)
    Random
      .shuffle(Range(0, numNeurons).toList)
      .take(neuronsFiring)
      .map(index => OutgoingMessage(s"out-$index,$fireTime,1"))
  }

}

object NetworkCommander {
  sealed trait NetworkMessage
  case class Build(actor: ActorRef)
  case class IncomingMessage(text: String) extends NetworkMessage
  case class OutgoingMessage(text: String) extends NetworkMessage
  case class SendMessage() extends NetworkMessage
  case class NetworkCommand(command: String) extends NetworkMessage

  def props(name: String, manager: ActorRef) = Props(new NetworkCommander(name, manager))
}
