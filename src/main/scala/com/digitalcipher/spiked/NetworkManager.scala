package com.digitalcipher.spiked

import akka.actor.{Actor, ActorLogging, ActorRef, Terminated}
import com.digitalcipher.spiked.NetworkManager.AddNetwork

class NetworkManager extends Actor with ActorLogging {

  import context._

  /**
    * @return a [[Receive]] instance and starts with an empty map of networks
    */
  override def receive: Receive = updateNetworks(Map.empty)

  /**
    * Updates the networks by adding the new network or removing an existing one
    * @param networks The map holding the network's actor-refs to the network name
    * @return A [[Receive]] instance
    */
  final def updateNetworks(networks: Map[ActorRef, String]): Receive = {
    case AddNetwork(name) =>
      log.info(s"Adding network; name: $name")
      watch(sender())
      become(updateNetworks(networks + (sender() -> name)))

    case Terminated(network) =>
      log.info(s"Removing network; name: ${networks(network)}")
      become(updateNetworks(networks - network))
  }
}

object NetworkManager {

  case class AddNetwork(name: String)

}

//class NetworkManager extends Actor with ActorLogging {
//  private var networks: Map[ActorRef, String] = Map.empty
//
//  import context._
//  override def receive: Receive = {
//    case AddNetwork(name) =>
//      log.info(s"Adding network; name: $name")
//
//      // update the networks map (immutable so create new reference)
//      networks += (sender() -> name)
//      watch(sender())
//
//    case Terminated(network) =>
//      log.info(s"Removing network; name: ${networks(network)}")
//
//      // update the networks map (immutable so create new reference)
//      networks -= network
//  }
//}
