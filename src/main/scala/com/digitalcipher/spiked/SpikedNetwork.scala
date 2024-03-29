package com.digitalcipher.spiked

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, PoisonPill, Props}
import com.digitalcipher.spiked.NetworkManager.AddNetwork
import com.digitalcipher.spiked.SpikedNetwork.{Build, IncomingMessage, OutgoingMessage, SendMessage}

import scala.concurrent.ExecutionContextExecutor
import scala.util.Random

class SpikedNetwork(name: String, manager: ActorRef) extends Actor with ActorLogging {

  implicit val executionContext: ExecutionContextExecutor = context.dispatcher
  import scala.concurrent.duration._

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
    case Build(wsActor) =>
      log.info(s"building network; name: $name")
      // transition to the waiting state
      context become waiting(wsActor)
      // send the manager the name of the network
      manager ! AddNetwork(name)
  }

  /**
    * State where the network is waiting to be started. At this point the network is already
    * built.
    * @param wsActor The web-socket actor passed from the uninitialized state.
    * @return a receive instance
    */
  def waiting(wsActor: ActorRef): Receive = {
    case IncomingMessage(text) => text match {
      case "start" =>
        log.info(s"starting network; name: $name")

        // todo ultimately, this will be replaced by a source from the kafka
        val cancellable = context.system.scheduler.schedule(
          initialDelay = 0 seconds,
          interval = 20 milliseconds,
          receiver = self,
          SendMessage()
        )

        context become running(wsActor, System.currentTimeMillis(), cancellable)

      case "destroy" =>
        log.info(s"destroying network; name: $name")
        wsActor ! PoisonPill
    }
  }

  /**
    * In this state, the network is running
    * @param actor The web-socket actor to which to send the messages
    * @param startTime The start time of the simulation (i.e. when the network transitioned to this state
    * @param cancellable The cancellable for the scheduled (will disappear when data is coming from kafka)
    * @return a receive instance
    */
  def running(actor: ActorRef, startTime: Long, cancellable: Cancellable): Receive = {
    case SendMessage() =>
      messages(10, startTime).foreach(message => actor ! message)

    case IncomingMessage(text) => text match {
      case "stop" =>
        log.info(s"stopping network; name: $name")
        cancellable.cancel()

        context become waiting(actor)
    }
  }

  /**
    * get a random number of messages (i.e. some random set of neurons spike)
    *
    * @param numNeurons The number of neurons for which signals are sent
    * @return a list of text-messages
    */
  private def messages(numNeurons: Int, startTime: Long): List[OutgoingMessage] = {
    val fireTime = System.currentTimeMillis() - startTime
//    println(fireTime)
    val neuronsFiring = Random.nextInt(numNeurons)
    Random
      .shuffle(Range(0, numNeurons).toList)
      .take(neuronsFiring)
      .map(index => OutgoingMessage(s"out-$index,$fireTime,1"))
  }

}

object SpikedNetwork {
  sealed trait NetworkMessage
  case class Build(actor: ActorRef)
  case class IncomingMessage(text: String) extends NetworkMessage
  case class OutgoingMessage(text: String) extends NetworkMessage
  case class SendMessage() extends NetworkMessage

  def props(name: String, manager: ActorRef) = Props(new SpikedNetwork(name, manager))
}
