package com.digitalcipher.spiked.json

import com.digitalcipher.spiked.NetworkCommander.{AddSensorMessage, IncomingSignal, StartNetworkMessage}
import spray.json.DefaultJsonProtocol
import squants.electro.ElectricPotential

object SensorJsonSupport extends DefaultJsonProtocol {
  /*
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
   */
  import spray.json._

  //AddSensorMessage(name: String, selector: Regex)
  implicit object AddSensorMessageFormat extends RootJsonFormat[AddSensorMessage] {
    def write(message: AddSensorMessage): JsObject = JsObject(
      "name" -> JsString(message.name),
      "selector" -> JsString(message.selector.regex))

    def read(message: JsValue): AddSensorMessage = {
      message.asJsObject.getFields("name", "selector") match {
        case Seq(JsString(sensorName), JsString(selector)) => AddSensorMessage(
          name = sensorName,
          selector = selector.r)
        case _ => deserializationError("AddSensorMessage expected")
      }
    }
  }

  implicit object StartNetworkMessageFormat extends RootJsonFormat[StartNetworkMessage] {
    def write(message: StartNetworkMessage): JsObject = JsObject(
      "name" -> JsString(message.name),
      "selector" -> JsString(message.selector.regex))

    def read(message: JsValue): StartNetworkMessage = {
      message.asJsObject.getFields("name", "selector") match {
        case Seq(JsString(sensorName), JsString(selector)) => StartNetworkMessage(
          name = sensorName,
          selector = selector.r)
        case _ => deserializationError("StartNetworkMessage expected")
      }
    }
  }

  //IncomingSignal(sensorName: String, neuronIds: Seq[String], signal: ElectricPotential)
  import com.digitalcipher.spiked.logging.json.SpikeEventsJsonProtocol.ElectricPotentialJsonFormat
  implicit object IncomingSignalFormat extends RootJsonFormat[IncomingSignal] {
    def write(message: IncomingSignal): JsObject = JsObject(
      "sensorName" -> JsString(message.sensorName),
      "neuronIds" -> JsArray(message.neuronIds.map(id => JsString(id)).toVector),
      "signal" -> message.signal.toJson)

    def read(message: JsValue): IncomingSignal = {
      message.asJsObject.getFields("sensorName", "neuronIds", "signal") match {
        case Seq(JsString(sensorName), JsArray(neuronIds), signal) => IncomingSignal(
          sensorName = sensorName,
          neuronIds = neuronIds.map(id => id.convertTo[String]),
          signal = signal.convertTo[ElectricPotential])
        case _ => deserializationError("IncomingSignal expected")
      }
    }
  }

}
