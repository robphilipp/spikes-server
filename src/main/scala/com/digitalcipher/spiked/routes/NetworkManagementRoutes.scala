package com.digitalcipher.spiked.routes

import java.util.Base64

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import com.digitalcipher.spiked.NetworkCommander
import com.digitalcipher.spiked.NetworkCommanderManager.AddNetworkCommander
import com.digitalcipher.spiked.json.NetworkManagementJsonSupport
import com.digitalcipher.spiked.routes.NetworkManagementRoutes.{CreateNetworkCommander, CreateNetworkCommanderResponse}
import com.typesafe.config.Config

import scala.concurrent.Await
import scala.util.Random


class NetworkManagementRoutes(networkManagePath: String,
                              networkCommanderManager: ActorRef,
                              actorSystem: ActorSystem,
                              kafkaConfig: Config) extends NetworkManagementJsonSupport {
  import scala.concurrent.duration._
  implicit val timeout: Timeout = Timeout(1.seconds)

  lazy val networkManagementRoutes: Route = pathPrefix(networkManagePath / "network") {
    post {
      entity(as[CreateNetworkCommander]) { request =>
        // todo make this a GUID
        // create an ID for the network from a random number
//        val id = Base64.getEncoder.encodeToString(s"network-${Random.nextLong()}-${System.currentTimeMillis()}".getBytes)
        val id = s"${Random.nextLong()}-${System.currentTimeMillis()}".getBytes.map("%02X".format(_)).mkString

        // creates the network commander actor
        val networkCommander = actorSystem.actorOf(NetworkCommander.props(
          id, networkCommanderManager, kafkaConfig, request.kafkaSettings)
        )

        // registers the network commander manager with the manager
        Await.result(networkCommanderManager.ask(AddNetworkCommander(id, networkCommander)), timeout.duration)

        // todo once the UI sends the network description, then have to build the network, and use that
        // to return the size of the network
        complete(CreateNetworkCommanderResponse(id, request.networkDescription))
      }
    }
  }
}

object NetworkManagementRoutes {
  def apply(networkManagePath: String, networkManager: ActorRef, actorSystem: ActorSystem, kafkaConfig: Config) =
    new NetworkManagementRoutes(networkManagePath, networkManager, actorSystem, kafkaConfig: Config)

  trait NetworkManagementRequest
  case class CreateNetworkCommander(networkDescription: String, kafkaSettings: KafkaSettings) extends NetworkManagementRequest
  // todo use the below CreateNetwork class once the UI sends the network description
//  case class CreateNetwork(networkDescription: String, kafkaSettings: KafkaSettings) extends NetworkManagementRequest
  case class CreateNetworkCommanderResponse(id: String, networkDescription: String) extends NetworkManagementRequest

  case class KafkaSettings(bootstrapServers: Seq[KafkaServer])
  case class KafkaServer(host: String, port: Int)
}
