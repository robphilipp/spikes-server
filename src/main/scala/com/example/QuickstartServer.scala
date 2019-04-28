package com.example


import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.http.scaladsl.server.Directives._
import com.typesafe.config.ConfigFactory

object QuickstartServer extends App
  with StaticContentRoutes
  with UserRoutes
  with WebSocketRoutes
  with RandomRoutes
  with AuctionRoutes {
  // load the configuration
  val config = ConfigFactory.parseResources("application.conf")
  val hostname = config.getString("http.ip")
  val port = config.getInt("http.port")

  // set up ActorSystem and other dependencies here
  implicit val system: ActorSystem = ActorSystem("helloAkkaHttpServer")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContext = system.dispatcher

  val userRegistryActor: ActorRef = system.actorOf(UserRegistryActor.props, "userRegistryActor")
  val auctionActor: ActorRef = system.actorOf(Props[Auction], "auctionActor")

  // from the UserRoutes trait
  lazy val routes: Route = userRoutes ~ webSocketRoutes ~ randomRoutes ~ auctionRoutes ~ staticContentRoutes
  val serverBinding: Future[Http.ServerBinding] = Http().bindAndHandle(routes, hostname, port)

  serverBinding.onComplete {
    case Success(bound) =>
      println(s"Server online at http://${bound.localAddress.getHostString}:${bound.localAddress.getPort}/")
    case Failure(e) =>
      Console.err.println(s"Server could not start!")
      e.printStackTrace()
      system.terminate()
  }

  Await.result(system.whenTerminated, Duration.Inf)
}


