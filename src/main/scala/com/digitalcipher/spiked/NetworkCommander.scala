package com.digitalcipher.spiked

import java.util.Properties
import java.util.concurrent.TimeUnit

import akka.Done
import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props}
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink}
import com.digitalcipher.spiked.NetworkCommander._
import com.digitalcipher.spiked.apputils.SeriesRunner
import com.digitalcipher.spiked.inputs.PeriodicEnvironmentFactory
import com.digitalcipher.spiked.routes.NetworkManagementRoutes.KafkaSettings
import com.typesafe.config.Config
import org.apache.kafka.clients.admin.{AdminClient, AdminClientConfig}
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord}
import org.apache.kafka.common.serialization.StringDeserializer
import squants.Time
import squants.electro.{ElectricPotential, Millivolts}
import squants.time.{Milliseconds, Seconds}

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Random, Success, Try}
import scala.util.matching.Regex

class NetworkCommander(
                        id: String,
                        networkDescription: String,
                        manager: ActorRef,
                        kafkaConfig: Config,
                        kafkaSettings: KafkaSettings) extends Actor with ActorLogging {

  implicit val executionContext: ExecutionContextExecutor = context.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer.create(context)

  /**
    * Sets the receive function to the uninitialized function.
    *
    * @return a receive instance
    */
  override def receive: Receive = uninitialized

  /**
    * The initial state of the network commander actor. When it receives a command to build the
    * network, it creates the kafka consumer stream, sets up the consumer controller, and
    * transitions to the `ready` state.
    *
    * @return a receive instance
    */
  def uninitialized: Receive = {
    // builds the network when the connection is established. The "outgoingMessageActor" is the web-socket
    // actor to which messages are sent. Recall that the web-socket route has a handler, and
    // the handler returns a flow. The flow is a sink-to-source flow where the sink and source
    // are decoupled, except through this actor. This source sends messages to the web-socket
    // sink, which sends them back to the UI client.
    case BuildNetwork(outgoingMessageActor, seriesRunner) =>
      // as the network is being built, it will publish messages describing the network. so at this
      // point we already need to start consuming the messages and sending them down the websocket
      // to the client UI. Note that this consumer will read all the messages from kafka and forward
      // them to the web-socket. This is just a forwarder of the messages that the network builder
      // and series runner (which could be on different nodes) send to kafka
      val (consumerControl, future): (Consumer.Control, Future[Done]) = Consumer
        .plainSource(consumerSettings(id, kafkaSettings), Subscriptions.topics(s"$id-1"))
        .toMat(Sink.foreach(record => outgoingMessageActor ! OutgoingMessage(record.value())))(Keep.both)
        .run()

      // set up a handler for when the simulation is completed and has stopped
      future.onComplete(_ => self ! SimulationStopped())

      // todo deal with the error condition properly

      // transition to the state where the network is built, but not yet running
      context.become(ready(outgoingMessageActor, consumerControl, seriesRunner))
  }

  /**
    * The function for the networks `ready` state. In this state, the kafka consumer stream is set up
    * to receive messages from the network and forward them to the out-going (websocket) actor. When the
    * network commander receives the `build` command, it builds the network and then transitions to
    * the `built` state.
    *
    * Recall that during the build process, the network will emit messages to the logger (kafka in this
    * case) that describe the networks topology, connections, and learning functions. These messages
    * are sent to the out-going (websocket) actor.
    *
    * @param outgoingMessageActor The web-socket actor passed from the uninitialized state.
    * @param consumerControl      The consumer control that allows the simulation to be stopped and
    *                             when the simulation is complete, dispatches message that the simulation
    *                             has stopped
    * @param seriesRunner         The spikes network runner that runs the simulation
    * @return A receive instance
    */
  def ready(
             outgoingMessageActor: ActorRef,
             consumerControl: Consumer.Control,
             seriesRunner: SeriesRunner): Receive = {
    case IncomingMessage(text) => text.replaceAll("\"", "") match {
      case BUILD_COMMAND.name =>
        log.info(s"(ready) building network commander; id: $id")

        // build the network
        val networkResults = seriesRunner.createNetworks(num = 1, networkDescription, reparseReport = false)
        if (networkResults.hasFailures) {
          seriesRunner.logger.error(s"Failed to create all networks; failures: ${networkResults.failures.mkString}")
        }

        log.info(s"(ready) network built and ready to run; id: $id; kafka-settings: $kafkaSettings")
        context.become(built(outgoingMessageActor, networkResults, consumerControl, seriesRunner))

      case command => log.error(s"(ready) Invalid network command; command: $command")
    }
  }

  /**
    * This function describes the `built` state. In this state the network has been built and is ready
    * to start running and accepting inputs from its environment. In this state, the network commander
    * accepts three commands: `build`, `start`, and `destroy`.
    *
    * ===Commands===
    * 1.  `build` -- transition propagates the message, just log the transition
    * 2.  `start` -- starts the network simulation and transitions to the `running` state
    * 3.  `destroy` -- destroys the network, deletes up the topic and transitions to the `unitialized` state
    *
    * @param outgoingMessageActor The web-socket actor passed from the ready state.
    * @param networkResults       The results of building the network
    * @param consumerControl      The consumer control that allows the simulation to be stopped and
    *                             when the simulation is complete, dispatches message that the simulation
    *                             has stopped
    * @param seriesRunner         The spikes network runner that runs the simulation
    * @return a receive instance
    */
  def built(
             outgoingMessageActor: ActorRef,
             networkResults: SeriesRunner.CreateNetworkResults,
             consumerControl: Consumer.Control,
             seriesRunner: SeriesRunner): Receive = {

    case IncomingMessage(text) =>
      import spray.json._
      import com.digitalcipher.spiked.json.SensorJsonSupport._

      Try(JsonParser(cleanJson(text))) match {
        case Success(value) => Try(value.convertTo[AddSensorMessage]) match {
          case Success(AddSensorMessage(name, selector)) =>
            log.info(s"(built) adding sensor to network; id: $id; sensor_name: $name; selector: ${selector.regex}")
            seriesRunner.addSensor(name, selector, networkResults.successes)

          case _ =>
            log.error(s"(built) invalid message for built state; id: $id; message: $text")
        }

        case Failure(exception) => text.replaceAll("\"", "") match {
          case BUILD_COMMAND.name =>
            log.info(s"(built) Network built and ready to start; id: $id")

          case START_COMMAND.name =>
            log.info(s"(built) starting network; id: $id; kafka-settings: $kafkaSettings")

            if (seriesRunner.hasSensors(networkResults.successes.map(result => result.system.name))) {

              log.info(s"(built) started simulation; id: $id; sensors: ${seriesRunner.hasSensors(networkResults.successes.map(result => result.system.name))}")

              // transition to the running state
              context.become(running(outgoingMessageActor, System.currentTimeMillis(), consumerControl, networkResults, seriesRunner))
            } else {
              log.error(s"(built) cannot start simulation because no sensors have been created")
              // todo move code to create the environment factory outside of this function/class
              //    so that it can be configured by the UI
              // create the environment factory using the signal-function factory
              val environmentFactory = PeriodicEnvironmentFactory(
                initialDelay = Milliseconds(0),
                signalPeriod = Milliseconds(50),
                simulationDuration = Seconds(50),
                signalsFunction = randomNeuronSignalGeneratorFunction(Milliseconds(25)))

              // todo the input selector needs to be passed in (configured from the UI)
              // run the simulation, which will end after the time specified in the environment factory
              seriesRunner.runSimulationSeries(
                networkResults = networkResults.successes,
                environmentFactory = environmentFactory,
                inputNeuronSelector = """(in\-[1-7]$)""".r)
              // todo ---- end
            }
        }

        case command =>
          log.error(s"(built) Invalid network command; command: $command")
      }
//      Try(JsonParser(cleanJson(text)).convertTo[AddSensorMessage]) match {
//        case Success(AddSensorMessage(name, selector)) =>
//          log.info(s"(built) adding sensor to network; id: $id; sensor_name: $name; selector: ${selector.regex}")
//          seriesRunner.addSensor(name, selector, networkResults.successes)
//
//        case _ => text.replaceAll("\"", "") match {
//          case BUILD_COMMAND.name =>
//            log.info(s"(built) Network built and ready to start; id: $id")
//
//          case START_COMMAND.name =>
//            log.info(s"(built) starting network; id: $id; kafka-settings: $kafkaSettings")
//
//            if (seriesRunner.hasSensors(networkResults.successes.map(result => result.system.name))) {
//
//              log.info(s"(built) started simulation; id: $id; sensors: ${seriesRunner.hasSensors(networkResults.successes.map(result => result.system.name))}")
//
//              // transition to the running state
//              context.become(running(outgoingMessageActor, System.currentTimeMillis(), consumerControl, networkResults, seriesRunner))
//            } else {
//              log.error(s"(built) cannot start simulation because no sensors have been created")
//              // todo move code to create the environment factory outside of this function/class
//              //    so that it can be configured by the UI
//              // create the environment factory using the signal-function factory
//              val environmentFactory = PeriodicEnvironmentFactory(
//                initialDelay = Milliseconds(0),
//                signalPeriod = Milliseconds(50),
//                simulationDuration = Seconds(50),
//                signalsFunction = randomNeuronSignalGeneratorFunction(Milliseconds(25)))
//
//              // todo the input selector needs to be passed in (configured from the UI)
//              // run the simulation, which will end after the time specified in the environment factory
//              seriesRunner.runSimulationSeries(
//                networkResults = networkResults.successes,
//                environmentFactory = environmentFactory,
//                inputNeuronSelector = """(in\-[1-7]$)""".r)
//              // todo ---- end
//            }
//        }
//
//        case command =>
//          log.error(s"(built) Invalid network command; command: $command")
//      }

    case DestroyNetwork() =>
      log.info(s"(built) destroying network; id: $id")
      outgoingMessageActor ! PoisonPill

      // delete the topic
      val topics = topicsToDelete(1, id)
      val kafkaAdminClient = AdminClient.create(asProperties(kafkaSettings))
      kafkaAdminClient.deleteTopics(topics)
      log.info(s"(built) deleted kafka topics; topics: $topics")
      kafkaAdminClient.close(5, TimeUnit.SECONDS)

      // suicide
      self ! PoisonPill

    case _ => log.error(s"(built) Invalid incoming message type")
  }

  /**
    * In this state, the network is running.
    *
    * @param outgoingMessageActor The web-socket actor to which to send the messages
    * @param startTime            The start time of the simulation (i.e. when the network transitioned to this state
    * @param consumerControl      A tuple holding the consumer control, which can be used to stop the kafka consumer, and a future
    *                             that is completed once the simulation is stopped or completed
    * @return a receive instance
    */
  def running(
               outgoingMessageActor: ActorRef,
               startTime: Long,
               consumerControl: Consumer.Control,
               networkResults: SeriesRunner.CreateNetworkResults,
               seriesRunner: SeriesRunner): Receive = {

    case SimulationStopped() =>
      log.info(s"(running) simulation completed; id: $id")
      consumerControl.stop()
      context.become(built(outgoingMessageActor, networkResults, consumerControl, seriesRunner))

    case IncomingSignal(sensorName, neuronIds, signal) =>
      val actorSystems = networkResults.successes.map(result => result.system)
      seriesRunner.sendSensorSignal(sensorName, signal, neuronIds, actorSystems)


    case IncomingMessage(text) =>
      import spray.json._
      import com.digitalcipher.spiked.json.SensorJsonSupport._
      Try(JsonParser(cleanJson(text)).convertTo[IncomingSignal]) match {
        case Success(IncomingSignal(sensorName, neuronIds, signal)) =>
          log.info(s"(running) incoming signal; id: $id; sensor_name: $sensorName; neurons: $neuronIds; signal: $signal")
          seriesRunner.sendSensorSignal(sensorName, signal, neuronIds, networkResults.successes.map(result => result.system))

        case _ => text.replaceAll("\"", "") match {

          case STOP_COMMAND.name =>
            log.info(s"(running) stopping network; id: $id")
            consumerControl.stop()
            context.become(built(outgoingMessageActor, networkResults, consumerControl, seriesRunner))

          case command => log.error(s"(running) Invalid network command; command: $command")
        }
      }

    case message => log.error(s"(running) Invalid message type; message type: ${message.getClass.getName}")
  }
//  def running(
//               outgoingMessageActor: ActorRef,
//               startTime: Long,
//               consumerControl: Consumer.Control,
//               networkResults: SeriesRunner.CreateNetworkResults,
//               seriesRunner: SeriesRunner): Receive = {
//
//    case SimulationStopped() =>
//      log.info(s"(running) simulation completed; id: $id")
//      consumerControl.stop()
//      context.become(built(outgoingMessageActor, networkResults, consumerControl, seriesRunner))
//
//    case IncomingSignal(sensorName, neuronIds, signal) =>
//      val actorSystems = networkResults.successes.map(result => result.system)
//      seriesRunner.sendSensorSignal(sensorName, signal, neuronIds, actorSystems)
//
//    case IncomingMessage(text) => text.replaceAll("\"", "") match {
//
//      case STOP_COMMAND.name =>
//        log.info(s"(running) stopping network; id: $id")
//        consumerControl.stop()
//        context.become(built(outgoingMessageActor, networkResults, consumerControl, seriesRunner))
//
//      case command => log.error(s"(running) Invalid network command; command: $command")
//    }
//
//    case message => log.error(s"(running) Invalid message type; message type: ${message.getClass.getName}")
//  }

  private def consumerSettings(networkId: String, kafkaSettings: KafkaSettings): ConsumerSettings[String, String] = {
    ConsumerSettings(kafkaConfig, new StringDeserializer, new StringDeserializer)
      .withBootstrapServers(kafkaSettings.bootstrapServers.map(server => s"${server.host}:${server.port}").mkString(","))
      .withGroupId(networkId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
  }

  /**
    * Some 'splainin: currently the ui sends the add-sensor method formatted by JSON.stringify(...)
    * and so the object comes through as a string where the json has the escaped quotes, for example
    * `"{\"name\":\"test-sensor\",\"selector\":\"^in-1$\"}"`. this string needs to be converted to a
    * string-representation of an object, so we need to get rid of the \ characters (escape) and the
    * leading and trailing quotes.
    * @param text The stringified JSON (i.e. javascript JSON.stringify(..))
    * @return A string-representation of a json object
    */
  private def cleanJson(text: String): String = text.replace("\\", "").replaceAll("^\"|\"$", "")
}

object NetworkCommander {

  val BUILD_COMMAND: Symbol = 'build
  val START_COMMAND: Symbol = 'start
  val STOP_COMMAND: Symbol = 'stop
  val DESTROY_COMMAND: Symbol = 'destroy

  sealed trait NetworkMessage

  case class BuildNetwork(actor: ActorRef, seriesRunner: SeriesRunner)

  case class AddSensorMessage(name: String, selector: Regex) extends NetworkMessage

  case class DestroyNetwork()

  case class Link(actor: ActorRef)

  case class IncomingMessage(text: String) extends NetworkMessage

  case class IncomingSignal(sensorName: String, neuronIds: Seq[String], signal: ElectricPotential)

  case class OutgoingMessage(text: String) extends NetworkMessage

  case class SendMessage() extends NetworkMessage

  case class SimulationStopped() extends NetworkMessage

  case class NetworkCommand(command: String) extends NetworkMessage

  case class SendRecord(record: ConsumerRecord[String, String])

  def props(name: String, networkDescription: String, manager: ActorRef, kafkaConfig: Config, kafkaSettings: KafkaSettings): Props =
    Props(new NetworkCommander(name, networkDescription, manager, kafkaConfig, kafkaSettings))

  /**
    * Converts the scala configuration into a Java properties
    *
    * @param settings The kafka settings
    * @return A java properties
    */
  def asProperties(settings: KafkaSettings): Properties = {
    import scala.collection.JavaConverters._

    val props = new Properties()
    props.put(
      AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
      settings.bootstrapServers.map(server => s"${server.host}:${server.port}").asJava)
    props
    //    import scala.collection.JavaConverters._
    //    val props = new Properties()
    //    config.entrySet().asScala.foreach(entry => props.put(entry.getKey, entry.getValue.unwrapped()))
    //    props
  }

  def topicsToDelete(numSeries: Int, id: String): java.util.Collection[String] = {
    import scala.collection.JavaConverters._
    List.range(1, numSeries + 1).map(i => s"$id-$i").asJava
  }

  val random = new Random(System.currentTimeMillis())

  /**
    * Creates a function that accepts a sequence of neurons and a time. When the function is called, it sends
    * a signal to one of the neurons in the set, picked at random, if the elapsed time since the last call
    * exceeds the `minSignalInterval` time. For example, suppose that there are 10 input neurons
    * (n,,1,,, n,,2,,, ...., n,,10,,), and the min signal time is 25 ms. If the environment has a period of 50 ms,
    * then every 50 ms it will call the function returned from this method, handing it a sequence holding the
    * input neurons, n,,i,,. This function checks if it has been called within the last 25 ms, and if not,
    * picks one neuron from the sequence at random, resets the time, and returns a map holding the actor
    * reference to the input neuron with the associate signal strength (in mV).
    *
    * @param minSignalInterval The smallest elapsed time from the previous call that a signal will be sent
    * @return A function that accepts a sequence of input neurons and a time and returns a map of neurons that are
    *         to receive a signal and the strength of that signal
    */
  private def randomNeuronSignalGeneratorFunction(minSignalInterval: Time): (Seq[ActorRef], Time) => Map[ActorRef, ElectricPotential] = {
    var index: Int = 0
    var startTime: Time = Milliseconds(0)

    (neurons, time) => {
      if (time - startTime > minSignalInterval) {
        index = random.nextInt(neurons.length)
        startTime = time
      }
      Map(neurons(index) -> Millivolts(1.05))
      //      Map(neurons(random.nextInt(neurons.length)) -> Millivolts(1.05))
    }
  }

}
