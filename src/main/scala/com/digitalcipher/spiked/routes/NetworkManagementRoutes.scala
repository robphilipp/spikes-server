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
import com.typesafe.config.Config

import scala.concurrent.Await
import scala.util.Random


class NetworkManagementRoutes(networkManagePath: String,
                              networkManager: ActorRef,
                              actorSystem: ActorSystem,
                              kafkaConfig: Config) extends NetworkManagementJsonSupport {
  import scala.concurrent.duration._
  implicit val timeout: Timeout = Timeout(1.seconds)

  lazy val networkManagementRoutes: Route = pathPrefix(networkManagePath / "network") {
    post {
      entity(as[CreateNetwork]) { request =>
        // todo make this a GUID
        // create an ID for the network from a random number
        val id = s"network-${Random.nextInt()}"
        // creates the network commander actor
        val network = actorSystem.actorOf(NetworkCommander.props(id, networkManager, kafkaConfig, request.kafkaSettings))
        // registers the network commander manager with the manager
        Await.result(networkManager.ask(AddNetwork(id, network)), timeout.duration)

        complete(CreateNetworkResponse(id, request.size))
      }
    }
  }
}

object NetworkManagementRoutes {
  def apply(networkManagePath: String, networkManager: ActorRef, actorSystem: ActorSystem, kafkaConfig: Config) =
    new NetworkManagementRoutes(networkManagePath, networkManager, actorSystem, kafkaConfig: Config)

  trait NetworkManagementRequest
  case class CreateNetwork(size: Int, kafkaSettings: KafkaSettings) extends NetworkManagementRequest
  case class CreateNetworkResponse(id: String, size: Int) extends NetworkManagementRequest

  case class KafkaSettings(bootstrapServers: Seq[KafkaServer])
  case class KafkaServer(host: String, port: Int)
}
