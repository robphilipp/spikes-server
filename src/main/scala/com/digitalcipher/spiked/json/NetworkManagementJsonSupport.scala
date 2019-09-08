package com.digitalcipher.spiked.json

import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.digitalcipher.spiked.routes.NetworkManagementRoutes.{CreateNetworkCommander, CreateNetworkCommanderResponse, DeleteNetworkCommanderResponse, KafkaServer, KafkaSettings}

trait NetworkManagementJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  /*
     create network request:
     {
        size: number,
        kafkaSettings: {
          kafkaServer: [
            {host: string, port: number}
          ]
        }
   */
  implicit val kafkaServerFormat: RootJsonFormat[KafkaServer] = jsonFormat2(KafkaServer)
  implicit val kafkaSettings: RootJsonFormat[KafkaSettings] = jsonFormat1(KafkaSettings)
  implicit val createNetworkRequestFormat: RootJsonFormat[CreateNetworkCommander] = jsonFormat2(CreateNetworkCommander)
  implicit val createNetworkResponseFormat: RootJsonFormat[CreateNetworkCommanderResponse] = jsonFormat2(CreateNetworkCommanderResponse)
  implicit val deleteNetworkResponseFormat: RootJsonFormat[DeleteNetworkCommanderResponse] = jsonFormat1(DeleteNetworkCommanderResponse)
}
