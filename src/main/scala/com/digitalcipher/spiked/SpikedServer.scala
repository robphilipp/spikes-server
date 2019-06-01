package com.digitalcipher.spiked

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import com.digitalcipher.spiked.routes.{StaticContentRoutes, WebSocketRoutes}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
  * Server for static content and web sockets. Serves up the web page and
  * then streams data to the real-time visualizations.
  */
object SpikedServer extends App with StaticContentRoutes with WebSocketRoutes {
  // load the configuration
  private val config = ConfigFactory.parseResources("application.conf")
  private val hostname = config.getString("http.ip")
  private val port = config.getInt("http.port")

  // set up ActorSystem and other dependencies here
  implicit val actorSystem: ActorSystem = ActorSystem("spiked-network-server")

  // create the streams materializer and execution context
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContext = actorSystem.dispatcher

  private lazy val log = Logging(actorSystem, getClass)

  // create the combined routes (from WebSocketRoutes and StaticContentRoutes traits)
  lazy val routes: Route = webSocketRoutes ~ staticContentRoutes
  log.info(s"static-content route settings; " +
    s"base URL: $baseUrl; " +
    s"default page: $defaultPage; " +
    s"timeout: ${timeout.duration.toSeconds} s"
  )
  log.info(s"web-socket route settings; web-socket path: $webSocketPath")

  // attempt to start the server
  val serverBinding: Future[Http.ServerBinding] = Http().bindAndHandle(routes, hostname, port)
  log.info(s"Attempting to start the server; host: $hostname; port: $port")
  serverBinding.onComplete {
    case Success(bound) =>
      log.info(s"Server started; url: http://${bound.localAddress.getHostString}:${bound.localAddress.getPort}/")

    case Failure(e) =>
      log.error(s"Unable to start the server; exception: ${e.getMessage}")
      actorSystem.terminate()
  }

  Await.result(actorSystem.whenTerminated, Duration.Inf)
}
