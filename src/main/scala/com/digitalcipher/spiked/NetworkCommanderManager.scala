package com.digitalcipher.spiked

import akka.actor.{Actor, ActorLogging, ActorRef, Terminated}
import com.digitalcipher.spiked.NetworkCommanderManager.{AddNetworkCommander, AddedNetworkCommander, RetrieveNetworkCommanderById}

/**
  * Holds the network-commanders created by this system as associations between a network ID
  * and the network-commanders' actor reference. Handles the following messages:
  *
  *   1. [[com.digitalcipher.spiked.NetworkCommanderManager.AddNetworkCommander]] message causes the manager
  *       to add the network-commander and its associated ID
  *   2. [[com.digitalcipher.spiked.NetworkCommanderManager.RetrieveNetworkCommanderById]] message causes the
  *       manager to retrieve the actor reference associated with the specified network commander ID.
  *   3. [[akka.actor.Terminated]] message causes the manager to remove the network commander
  */
class NetworkCommanderManager extends Actor with ActorLogging {

  import context._

  /**
    * @return a [[Receive]] instance and starts with an empty map of networks
    */
  override def receive: Receive = updateNetworkCommanders(Map.empty, Map.empty)

  /**
    * Updates the network-commanders by adding the new network when receiving the [[AddNetworkCommander]] message,
    * retrieving the network-commanders' actor reference when receiving the [[RetrieveNetworkCommanderById]] message, and
    * removes the network-commander from management when receiving the actor's [[Terminated]] message.
 *
    * @param ids The map holding the association of the network IDs to the network actor references
    * @param networkCommanders The map holding association of network's actor references to the network IDs
    * @return A [[Receive]] instance
    */
  final def updateNetworkCommanders(ids: Map[String, ActorRef], networkCommanders: Map[ActorRef, String]): Receive = {
    case AddNetworkCommander(id, networkCommander) =>
      log.info(s"Adding network; network ID: $id")
      watch(networkCommander)
      sender() ! AddedNetworkCommander(id)
      become(updateNetworkCommanders(ids + (id -> networkCommander), networkCommanders + (networkCommander -> id)))

    case RetrieveNetworkCommanderById(id) =>
      // find the managed network for the specified ID. If found, then returns the
      // ManagedNetwork containing the ID and the actor-ref. If not found, returns
      // the NetworkNotFoundFor containing the requested ID.
      val network = ids.get(id).map(ref => Right(ref)).getOrElse(Left(id))

      sender() ! network
      log.info(s"Requested network for ID $id")

    case Terminated(networkCommander) =>
      // grab the network ID, and if found update the maps by removing the network ID and the
      // network actor-ref
      val (updateIds, updatedNetworkCommanders, id) = networkCommanders
        .get(networkCommander)
        .map(id => (ids - id, networkCommanders - networkCommander, id))
        .getOrElse((ids, networkCommanders, "[not found]"))

      log.info(s"Removing network; network ID: $id; network: $networkCommander")
      become(updateNetworkCommanders(updateIds, updatedNetworkCommanders))
  }
}

object NetworkCommanderManager {
  case class AddNetworkCommander(id: String, networkCommander: ActorRef)
  case class RetrieveNetworkCommanderById(id: String)
  case class AddedNetworkCommander(id: String)
}
