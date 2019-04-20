package com.example

import com.example.UserRegistryActor.ActionPerformed
import spray.json.RootJsonFormat

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.DefaultJsonProtocol

trait JsonSupport extends SprayJsonSupport {
  // import the default encoders for primitive types (Int, String, Lists etc)
  import DefaultJsonProtocol._

  implicit val userJsonFormat: RootJsonFormat[User] = jsonFormat3(User)
  implicit val usersJsonFormat: RootJsonFormat[Users] = jsonFormat1(Users)
  implicit val userAgeJsonFormat: RootJsonFormat[UserAge] = jsonFormat1(UserAge)

  implicit val actionPerformedJsonFormat: RootJsonFormat[ActionPerformed] = jsonFormat1(ActionPerformed)

  implicit val bidFormat: RootJsonFormat[Bid] = jsonFormat2(Bid)
  implicit val bidsFormat: RootJsonFormat[Bids] = jsonFormat1(Bids)
}
