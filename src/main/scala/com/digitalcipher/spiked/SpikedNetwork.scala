package com.digitalcipher.spiked

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, PoisonPill, Props}
import com.digitalcipher.spiked.NetworkManager.AddNetwork
import com.digitalcipher.spiked.SpikedNetwork.{Build, IncomingMessage, OutgoingMessage, SendMessage}

import scala.util.Random

class SpikedNetwork(name: String, manager: ActorRef) extends Actor with ActorLogging {
  private var startTime = System.currentTimeMillis()

  implicit val ec = context.dispatcher
  import scala.concurrent.duration._
  var cancellable: Cancellable = Cancellable.alreadyCancelled

  override def receive: Receive = uninitialized

  def uninitialized: Receive = {
    case Build(wsActor) =>
      log.info(s"building network; name: $name")
      context become waiting(wsActor)
      manager ! AddNetwork(name)
  }

  def waiting(actor: ActorRef): Receive = {
    case IncomingMessage(text) => text match {
      case "start" =>
        log.info(s"starting network; name: $name")

        startTime = System.currentTimeMillis()
        cancellable = context.system.scheduler.schedule(
          initialDelay = 0 seconds,
          interval = 20 milliseconds,
          receiver = self,
          SendMessage()
        )

        context become running(actor)

      case "destroy" =>
        log.info(s"destroying network; name: $name")
        actor ! PoisonPill
    }
  }

  def running(actor: ActorRef): Receive = {
//    case message: OutgoingMessage => message
    case SendMessage() =>
      actor ! OutgoingMessage(s"out-1,${System.currentTimeMillis() - startTime},1")

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
  private def messages(numNeurons: Int): List[OutgoingMessage] = {
    val fireTime = System.currentTimeMillis() - startTime
    println(fireTime)
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
