package com.example


import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.http.scaladsl.server.Directives._

object QuickstartServer extends App with StaticContentRoutes with UserRoutes with RandomRoutes with AuctionRoutes {

  // set up ActorSystem and other dependencies here
  implicit val system: ActorSystem = ActorSystem("helloAkkaHttpServer")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContext = system.dispatcher

  val userRegistryActor: ActorRef = system.actorOf(UserRegistryActor.props, "userRegistryActor")
  val auctionActor: ActorRef = system.actorOf(Props[Auction], "auctionActor")

  // from the UserRoutes trait
  lazy val routes: Route = userRoutes ~ randomRoutes ~ auctionRoutes ~ staticContentRoutes
  val serverBinding: Future[Http.ServerBinding] = Http().bindAndHandle(routes, "localhost", 8080)

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


