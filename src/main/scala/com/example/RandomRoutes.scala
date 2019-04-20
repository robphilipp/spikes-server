package com.example

import akka.actor.{ActorRef, ActorSystem}
import akka.event.Logging
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl._
import akka.util.{ByteString, Timeout}

import scala.concurrent.duration._
import scala.util.Random

trait RandomRoutes extends JsonSupport {
  // we leave these abstract, since they will be provided by the App
  implicit def system: ActorSystem

  private lazy val log = Logging(system, classOf[RandomRoutes])

  // other dependencies that UserRoutes use
  def userRegistryActor: ActorRef

  // Required by the `ask` (?) method below
  private implicit lazy val timeout: Timeout = Timeout(5.seconds) // usually we'd obtain the timeout from the system's configuration

  // streams are re-usable so we can define it here
  // and use it for every request
  private val numbers = Source.fromIterator(() => Iterator.continually(Random.nextInt()))

  lazy val randomRoutes: Route =
    pathPrefix("random") {
      get {
        complete(
          HttpEntity(
            ContentTypes.`text/plain(UTF-8)`,
            // transform each number to a chunk of bytes
            numbers.map(n => ByteString(s"$n\n"))
          )
        )
      }
    }

}
