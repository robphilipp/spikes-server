package com.example

import akka.actor.{Actor, ActorLogging}

class Auction extends Actor with ActorLogging{
  var bids = List.empty[Bid]

  override def receive: Receive = {
    case bid @ Bid(userId, offer) =>
      bids = bids :+ bid
      log.info("bid complete; user: {}; offer: {}", userId, offer)

    case GetBids => sender() ! Bids(bids)
    case _ => log.error("invalid message")
  }
}

final case class Bid(userId: String, offer: Int)
case object GetBids
final case class Bids(bids: List[Bid])

