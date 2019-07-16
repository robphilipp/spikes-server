package com.digitalcipher.spiked.routes

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import com.digitalcipher.spiked.NetworkCommander
import com.digitalcipher.spiked.NetworkCommanderManager.AddNetwork
import com.digitalcipher.spiked.json.NetworkManagementJsonSupport
import com.digitalcipher.spiked.routes.NetworkManagementRoutes.{CreateNetwork, CreateNetworkResponse}

import scala.concurrent.Await
import scala.util.Random


class NetworkManagementRoutes(networkManagePath: String,
                              networkManager: ActorRef,
                              actorSystem: ActorSystem) extends NetworkManagementJsonSupport {
  import scala.concurrent.duration._
  implicit val timeout: Timeout = Timeout(1.seconds)

  lazy val networkManagementRoutes: Route = pathPrefix(networkManagePath / "network") {
    post {
      entity(as[CreateNetwork]) { request =>
        val id = s"network-${Random.nextInt()}"
        // creates the actor, which registers itself with the manager
        val network = actorSystem.actorOf(NetworkCommander.props(id, networkManager))
        Await.result(networkManager.ask(AddNetwork(id, network)), timeout.duration)

        complete(CreateNetworkResponse(id, request.size))
      }
    }
  }
}

object NetworkManagementRoutes {
  def apply(networkManagePath: String, networkManager: ActorRef, actorSystem: ActorSystem) =
    new NetworkManagementRoutes(networkManagePath, networkManager, actorSystem)

  trait NetworkManagementRequest
  case class CreateNetwork(size: Int)
  case class CreateNetworkResponse(id: String, size: Int)
}
