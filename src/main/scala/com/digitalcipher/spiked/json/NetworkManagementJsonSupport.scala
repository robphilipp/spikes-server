package com.digitalcipher.spiked.json

import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.digitalcipher.spiked.json.JsonSupport.jsonFormat1
import com.digitalcipher.spiked.routes.NetworkManagementRoutes.{CreateNetwork, CreateNetworkResponse}

trait NetworkManagementJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val createNetworkRequestFormat: RootJsonFormat[CreateNetwork] = jsonFormat1(CreateNetwork)
  implicit val createNetworkResponseFormat: RootJsonFormat[CreateNetworkResponse] = jsonFormat2(CreateNetworkResponse)
}
