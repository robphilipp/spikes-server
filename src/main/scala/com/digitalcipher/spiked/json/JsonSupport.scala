package com.digitalcipher.spiked.json

import com.digitalcipher.spiked.NetworkCommander.{ BuildNetworkMessage, NetworkCommand }
import spray.json.DefaultJsonProtocol

object JsonSupport extends DefaultJsonProtocol {

  import spray.json._

  // import the default encoders for primitive types (Int, String, Lists etc)
  implicit object NetworkCommandFormat extends RootJsonFormat[NetworkCommand] {
    def write(command: NetworkCommand): JsObject = JsObject("command" -> JsString(command.command))

    def read(value: JsValue): NetworkCommand = {
      value.asJsObject.getFields("command") match {
        case Seq(JsString(command)) => NetworkCommand(command)
        case _ => deserializationError("NetworkCommand expected")
      }
    }
  }

  implicit object BuildNetworkMessageFormat extends RootJsonFormat[BuildNetworkMessage] {
    def write(message: BuildNetworkMessage): JsObject = JsObject("timeFactor" -> JsNumber(message.timeFactor))

    def read(value: JsValue): BuildNetworkMessage = {
      value.asJsObject.getFields("timeFactor") match {
        case Seq(JsNumber(timeFactor)) => BuildNetworkMessage(timeFactor.intValue())
        case _ => deserializationError("BuildNetworkMessage expected")
      }
    }
  }
}
