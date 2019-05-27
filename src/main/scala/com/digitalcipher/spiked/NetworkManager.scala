package com.digitalcipher.spiked

import akka.actor.{Actor, ActorLogging, ActorRef, Terminated}
import com.digitalcipher.spiked.NetworkManager.AddNetwork

class NetworkManager extends Actor with ActorLogging {
  private var networks: Map[ActorRef, String] = Map.empty

  override def receive: Receive = {
    case AddNetwork(name) =>
      log.info(s"Adding network; name: $name")
      networks += (sender() -> name)
      context.watch(sender())

    case Terminated(network) =>
      log.info(s"Removing network; name: ${networks(network)}")
      networks -= network
  }
}

object NetworkManager {
  case class AddNetwork(name: String)
}
