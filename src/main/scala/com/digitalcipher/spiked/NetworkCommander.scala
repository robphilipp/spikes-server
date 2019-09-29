package com.digitalcipher.spiked

import java.util.Properties

import akka.Done
import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props}
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink}
import akka.util.Timeout
import com.digitalcipher.spiked.NetworkCommander._
import com.digitalcipher.spiked.apputils.SeriesRunner
import com.digitalcipher.spiked.json.JsonSupport._
import com.digitalcipher.spiked.logging.KafkaEventLogger.KafkaConfiguration
import com.digitalcipher.spiked.routes.NetworkManagementRoutes.KafkaSettings
import com.typesafe.config.Config
import org.apache.kafka.clients.admin.{AdminClient, AdminClientConfig}
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord}
import org.apache.kafka.common.serialization.StringDeserializer
import spray.json._

import scala.concurrent.{ExecutionContextExecutor, Future}

class NetworkCommander(id: String,
                       networkDescription: String,
                       manager: ActorRef,
                       kafkaConfig: Config,
                       kafkaSettings: KafkaSettings
                      ) extends Actor with ActorLogging {

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
      // to the client UI.
      val consumer: (Consumer.Control, Future[Done]) = Consumer
        .plainSource(consumerSettings(id, kafkaSettings), Subscriptions.topics(s"$id-1"))
        //        .filter(record => record.key() == "fire")
        .toMat(Sink.foreach(record => {
          log.info(s"sending record: ${record.value().toString}")
          outgoingMessageActor ! OutgoingMessage(record.value().toString)
        }))(Keep.both)
        .run()

      // set up a handler for when the simulation is completed and has stopped
      consumer._2.onComplete(_ => self ! SimulationStopped())

      // todo deal with the error condition properly

      // transition to the state where the network is built, but not yet running
      context.become(ready(outgoingMessageActor /*, networkResults*/ , consumer._1, seriesRunner))
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
  def ready(outgoingMessageActor: ActorRef,
            consumerControl: Consumer.Control,
            seriesRunner: SeriesRunner
           ): Receive = {
    case IncomingMessage(text) => text.parseJson.convertTo match {
      case NetworkCommand(BUILD_COMMAND.name) =>
        log.info(s"(ready) building network commander; id: $id")

        // build the network
        val networkResults = seriesRunner.createNetworks(num = 1, description = networkDescription, reparseReport = false)
        if (networkResults.hasFailures) {
          seriesRunner.logger.error(s"Failed to create all networks; failures: ${networkResults.failures.mkString}")
        }

        log.info(s"(ready) network built and ready to run; id: $id; kafka-settings: $kafkaSettings")
        context.become(built(outgoingMessageActor, networkResults, consumerControl, seriesRunner))

      case NetworkCommand(command) => log.error(s"(ready) Invalid network command; command: $command")
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
  def built(outgoingMessageActor: ActorRef,
            networkResults: SeriesRunner.CreateNetworkResults,
            consumerControl: Consumer.Control,
            seriesRunner: SeriesRunner
           ): Receive = {
    case IncomingMessage(text) => text.parseJson.convertTo match {
      case NetworkCommand(BUILD_COMMAND.name) =>
        log.info(s"(built) Network built and ready to start; id: $id")

      case NetworkCommand(START_COMMAND.name) =>
        log.info(s"(built) starting network; id: $id; kafka-settings: $kafkaSettings")

        // todo start the simulation
        //        seriesRunner.runSimulationSeries(networkResults, , )

        // transition to the running state
        context.become(running(outgoingMessageActor, System.currentTimeMillis(), consumerControl, networkResults, seriesRunner))

      case NetworkCommand(command) =>
        log.error(s"(built) Invalid network command; command: $command")
    }

    case DestroyNetwork() =>
      log.info(s"(built) destroying network; id: $id")
      outgoingMessageActor ! PoisonPill

      // delete the topic
      val topics = topicsToDelete(1, id)
      val kafkaAdminClient = AdminClient.create(asProperties(kafkaSettings))
      kafkaAdminClient.deleteTopics(topics)
      log.info(s"(built) deleted kafka topics; topics: $topics")
      kafkaAdminClient.close()

      // suicide
      self ! PoisonPill

    case _ => log.error(s"Invalid incoming message type")
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
  def running(outgoingMessageActor: ActorRef,
              startTime: Long,
              consumerControl: Consumer.Control,
              networkResults: SeriesRunner.CreateNetworkResults,
              seriesRunner: SeriesRunner
             ): Receive = {

    case SimulationStopped() =>
      log.info(s"(running) simulation completed; id: $id")
      consumerControl.stop()
      context.become(built(outgoingMessageActor, networkResults, consumerControl, seriesRunner))

    case IncomingMessage(text) =>
      text.parseJson.convertTo match {

        case NetworkCommand(STOP_COMMAND.name) =>
          log.info(s"(running) stopping network; id: $id")
          consumerControl.stop()
          context.become(built(outgoingMessageActor, networkResults, consumerControl, seriesRunner))

        case NetworkCommand(command) => log.error(s"(running) Invalid network command; command: $command")
      }

    case message => log.error(s"(running) Invalid message type; message type: ${message.getClass.getName}")
  }


  private def consumerSettings(networkId: String, kafkaSettings: KafkaSettings): ConsumerSettings[String, String] = {
    ConsumerSettings(kafkaConfig, new StringDeserializer, new StringDeserializer)
      .withBootstrapServers(kafkaSettings.bootstrapServers.map(server => s"${server.host}:${server.port}").mkString(","))
      .withGroupId(networkId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
  }
}

object NetworkCommander {

  val BUILD_COMMAND: Symbol = 'build
  val START_COMMAND: Symbol = 'start
  val STOP_COMMAND: Symbol = 'stop
  val DESTROY_COMMAND: Symbol = 'destroy

  sealed trait NetworkMessage

  case class BuildNetwork(actor: ActorRef, seriesRunner: SeriesRunner)
  case class DestroyNetwork()

  case class Link(actor: ActorRef)

  case class IncomingMessage(text: String) extends NetworkMessage

  case class OutgoingMessage(text: String) extends NetworkMessage

  case class SendMessage() extends NetworkMessage

  case class SimulationStopped() extends NetworkMessage

  case class NetworkCommand(command: String) extends NetworkMessage

  case class SendRecord(record: ConsumerRecord[String, String])

  def props(name: String, networkDescription: String, manager: ActorRef, kafkaConfig: Config, kafkaSettings: KafkaSettings) =
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
    props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, settings.bootstrapServers.map(server => s"${server.host}:${server.port}").asJava)
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
}
