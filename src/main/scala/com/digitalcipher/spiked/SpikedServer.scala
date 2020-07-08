package com.digitalcipher.spiked

import java.nio.file.{ Path, Paths }
import java.util.concurrent.TimeUnit

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.digitalcipher.spiked.apputils.SpikesAppUtils.loadConfigFrom
import com.digitalcipher.spiked.apputils.{ SeriesRunner, SpikesAppUtils }
import com.digitalcipher.spiked.routes.{ NetworkManagementRoutes, StaticContentRoutes, WebSocketRoutes }
import com.typesafe.config.ConfigFactory

import scala.collection.JavaConverters._
import scala.concurrent.duration.{ Duration, _ }
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.{ Failure, Success }

/**
 * Server for static content and web sockets. Serves up the web page and
 * then streams data to the real-time visualizations.
 */
object SpikedServer extends App {
  // load the configuration
  private val config = loadConfigFrom("application.conf")
  private val hostname = config.getString("http.ip")
  private val port = config.getInt("http.port")
  private val kafkaConsumerConfig = config.getConfig("akka.kafka.consumer")

  // set up ActorSystem and other dependencies here
  implicit val actorSystem: ActorSystem = ActorSystem("spiked-network-server")

  // create the streams materializer and execution context
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContext = actorSystem.dispatcher
  private lazy val networkManager: ActorRef = actorSystem.actorOf(Props[NetworkCommanderManager], "spikes-network-manager")

  private lazy val log = Logging(actorSystem, getClass)

  // create the combined routes
  lazy val routes: Route = webSocketRoutes ~ staticContentRoutes ~ networkManagementRoutes

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

  /**
   * Constructs the HTTP routes for static content based on application configuration
   * @return The HTTP route for the static contents
   */
  private def staticContentRoutes: Route = {
    val baseUrl: String = Option(config.getString("http.baseUrl")).getOrElse("")
    val defaultPages: Seq[Path] = Option(config.getStringList("http.defaultPages"))
      .map(pages => pages.asScala.map(page => Paths.get(baseUrl, page)))
      .getOrElse(Seq())
    val timeout: Timeout = Option(config.getInt("http.timeoutSeconds"))
      .map(seconds => Timeout(seconds, TimeUnit.SECONDS))
      .getOrElse(Timeout(5.seconds))

    log.info(s"static-content route settings; " +
      s"base URL: $baseUrl; " +
      s"default pages: $defaultPages; " +
      s"timeout: ${timeout.duration.toSeconds} s")

    StaticContentRoutes(baseUrl, defaultPages, timeout).staticContentRoutes
  }

  private def webSocketRoutes: Route = {
    val webSocketPath: String = Option(config.getString("http.webSocketPath")).getOrElse("")
    log.info(s"web-socket route settings; web-socket path: $webSocketPath")
    WebSocketRoutes(webSocketPath, config, networkManager, actorSystem).webSocketRoutes
  }

  private def networkManagementRoutes: Route = {
    NetworkManagementRoutes("network-management", networkManager, actorSystem /*, config*/ , kafkaConsumerConfig).networkManagementRoutes
  }
}
