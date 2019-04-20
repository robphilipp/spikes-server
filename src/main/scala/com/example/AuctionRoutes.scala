package com.example

import akka.actor.{ActorRef, ActorSystem}
import akka.event.Logging
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.MethodDirectives.get
import akka.http.scaladsl.server.directives.RouteDirectives.complete
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._

trait AuctionRoutes extends JsonSupport {
  // we leave these abstract, since they will be provided by the App
  implicit def system: ActorSystem

  private lazy val log = Logging(system, classOf[RandomRoutes])

  // other dependencies that auction routes use
  def auctionActor: ActorRef

  // Required by the `ask` (?) method below
  private implicit lazy val timeout: Timeout = Timeout(5.seconds) // usually we'd obtain the timeout from the system's configuration

  lazy val auctionRoutes: Route = path("auction") {
    put {
      entity(as[Bid]) { bid =>
        auctionActor ! Bid(bid.userId, bid.offer)
        complete((StatusCodes.Accepted, "bid placed"))
      }
    } ~ get {
      implicit val timeout: Timeout = 5.seconds

      // query the actor for the current auction state
      val bids: Future[Bids] = (auctionActor ? GetBids).mapTo[Bids]
      complete(bids)
    }
  }
}
