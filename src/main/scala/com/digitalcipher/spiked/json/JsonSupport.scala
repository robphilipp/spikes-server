package com.digitalcipher.spiked.json

import com.digitalcipher.spiked.NetworkCommander.NetworkCommand
import spray.json.DefaultJsonProtocol

object JsonSupport extends DefaultJsonProtocol {

  import spray.json._

  // import the default encoders for primitive types (Int, String, Lists etc)
  implicit object NetworkCommandFormat extends RootJsonFormat[NetworkCommand] {
    def write(command: NetworkCommand) = JsObject("command" -> JsString(command.command))

    def read(value: JsValue): NetworkCommand = {
      value.asJsObject.getFields("command") match {
        case Seq(JsString(command)) => NetworkCommand(command)
        case _ => deserializationError("NetworkCommand expected")
      }
    }
  }
}
