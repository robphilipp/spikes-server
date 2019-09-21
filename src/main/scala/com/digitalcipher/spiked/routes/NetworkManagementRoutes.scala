package com.digitalcipher.spiked.routes

import java.util.Base64

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import com.digitalcipher.spiked.NetworkCommander
import com.digitalcipher.spiked.NetworkCommander.BuildNetwork
import com.digitalcipher.spiked.NetworkCommanderManager.{AddNetworkCommander, DeleteNetworkCommander}
import com.digitalcipher.spiked.apputils.SeriesRunner
import com.digitalcipher.spiked.apputils.SeriesRunner.KafkaEventLogging
import com.digitalcipher.spiked.json.NetworkManagementJsonSupport
import com.digitalcipher.spiked.routes.NetworkManagementRoutes.{CreateNetworkCommander, CreateNetworkCommanderResponse, DeleteNetworkCommanderResponse}
import com.typesafe.config.Config

import scala.concurrent.Await
import scala.util.Random


class NetworkManagementRoutes(networkManagePath: String,
                              networkCommanderManager: ActorRef,
                              actorSystem: ActorSystem,
//                              serverConfig: Config,
                              kafkaConsumerConfig: Config) extends NetworkManagementJsonSupport {

  import scala.concurrent.duration._

  implicit val timeout: Timeout = Timeout(1.seconds)

  // todo add delete route to delete a network-commander based on a network ID
  //  lazy val networkManagementRoutes: Route = pathPrefix(networkManagePath / "network") {
  lazy val networkManagementRoutes: Route = concat(
    // create a network commander and build the network
    path(networkManagePath / "network") {
      post {
        entity(as[CreateNetworkCommander]) { request =>
          // create an ID for the network from a random number and the current time
          val id = Base64
            .getUrlEncoder
            .encodeToString(s"${Random.nextInt()}-${System.currentTimeMillis()}".getBytes)
            .replace("=", "")

//          val seriesRunner = new SeriesRunner(
//            timeFactor = 1,
//            appLoggerName = "spikes-network-server",
//            config = serverConfig,
//            systemBaseName = id,
//            eventLogging = Seq(KafkaEventLogging())
//          )

          // creates the network commander actor
          val networkCommander = actorSystem.actorOf(NetworkCommander.props(
            name = id,
            manager = networkCommanderManager,
            kafkaConfig = kafkaConsumerConfig,
            kafkaSettings = request.kafkaSettings,
            networkDescription = request.networkDescription
          ))

//          val network: SeriesRunner.CreateNetworkResult = Await
//            .result(networkCommander.ask(Build(seriesRunner)), timeout.duration)
//            .asInstanceOf[SeriesRunner.CreateNetworkResult]

          // registers the network commander manager with the manager
          Await.result(networkCommanderManager.ask(AddNetworkCommander(id, networkCommander)), timeout.duration)

          // todo once the UI sends the network description, then have to build the network, and use that
          // to return the size of the network
          complete(CreateNetworkCommanderResponse(id, request.networkDescription))
        }
      }
    },

    // delete the network commander
    path(networkManagePath / "network" / Segment) { networkId =>
      delete {
        Await.result(networkCommanderManager.ask(DeleteNetworkCommander(networkId)), timeout.duration)

        complete(DeleteNetworkCommanderResponse(networkId))
      }
    }
  )
}

object NetworkManagementRoutes {
  def apply(networkManagePath: String, networkManager: ActorRef, actorSystem: ActorSystem/*, serverConfig: Config*/, kafkaConfig: Config) =
    new NetworkManagementRoutes(networkManagePath, networkManager, actorSystem/*, serverConfig*/, kafkaConfig)

  trait NetworkManagementRequest

  case class CreateNetworkCommander(networkDescription: String, kafkaSettings: KafkaSettings) extends NetworkManagementRequest

  // todo use the below CreateNetwork class once the UI sends the network description
  //  case class CreateNetwork(networkDescription: String, kafkaSettings: KafkaSettings) extends NetworkManagementRequest
  case class CreateNetworkCommanderResponse(id: String, networkDescription: String) extends NetworkManagementRequest

  case class DeleteNetworkCommanderResponse(id: String) extends NetworkManagementRequest

  case class KafkaSettings(bootstrapServers: Seq[KafkaServer])

  case class KafkaServer(host: String, port: Int)

}
