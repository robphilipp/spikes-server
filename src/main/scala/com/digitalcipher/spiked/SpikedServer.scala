package com.digitalcipher.spiked

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import com.digitalcipher.spiked.routes.{StaticContentRoutes, WebSocketRoutes}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

object SpikedServer extends App with StaticContentRoutes with WebSocketRoutes {
  // load the configuration
  val config = ConfigFactory.parseResources("application.conf")
  val hostname = config.getString("http.ip")
  val port = config.getInt("http.port")

  // set up ActorSystem and other dependencies here
  implicit val actorSystem: ActorSystem = ActorSystem("spiked-network-server")

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContext = actorSystem.dispatcher

//  val userRegistryActor: ActorRef = actorSystem.actorOf(UserRegistryActor.props, "userRegistryActor")
//  val auctionActor: ActorRef = actorSystem.actorOf(Props[Auction], "auctionActor")

  // from the UserRoutes trait
  lazy val routes: Route = webSocketRoutes ~ staticContentRoutes
  val serverBinding: Future[Http.ServerBinding] = Http().bindAndHandle(routes, hostname, port)

  serverBinding.onComplete {
    case Success(bound) =>
      println(s"Server online at http://${bound.localAddress.getHostString}:${bound.localAddress.getPort}/")
    case Failure(e) =>
      Console.err.println(s"Server could not start!")
      e.printStackTrace()
      actorSystem.terminate()
  }

  Await.result(actorSystem.whenTerminated, Duration.Inf)
}
