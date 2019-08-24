package com.digitalcipher.spiked

import akka.Done
import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, PoisonPill, Props}
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink}
import akka.util.Timeout
import com.digitalcipher.spiked.NetworkCommander._
import com.digitalcipher.spiked.json.JsonSupport._
import com.digitalcipher.spiked.routes.NetworkManagementRoutes.KafkaSettings
import com.typesafe.config.Config
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord}
import org.apache.kafka.common.serialization.StringDeserializer
import spray.json._

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.Random

class NetworkCommander(id: String,
                       manager: ActorRef,
                       kafkaConfig: Config,
                       kafkaSettings: KafkaSettings
                      ) extends Actor with ActorLogging {

  implicit val executionContext: ExecutionContextExecutor = context.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer.create(context)

  import scala.concurrent.duration._

  implicit val timeout: Timeout = Timeout(1.seconds)

  override def receive: Receive = uninitialized

  /**
    * The initial state of the network actor. Once a connection is opened, transitions to
    * the `waiting(...)` method, which is handed the web-socket actor that serves as the sink
    * to this source
    *
    * @return a receive instance
    */
  def uninitialized: Receive = {
    // builds the network when the connection is established. The "outgoingMessageActor" is the web-socket
    // actor to which messages are sent. Recall that the web-socket route has a handler, and
    // the handler returns a flow. The flow is a sink-to-source flow where the sink and source
    // are decoupled, except through this actor. This source sends messages to the web-socket
    // sink, which sends them back to the UI client.
    case Build(outgoingMessageActor) =>
      log.info(s"building network commander; id: $id")
      // todo 1. build the actual spiked network
      //      2. connect to kafka
      //      3. stream topology messages to the outgoing message actor

      // transition to the state where the network is built, but not yet running
      context.become(built(outgoingMessageActor))
  }

  /**
    * State where the network is waiting to be started. At this point the network is already built.
    *
    * When a "start" message is received on the web-socket from the UI client:
    * 1. the network is started,
    * 2. creates an akka stream that subscribes to kafka and sends each message from kafka to itself
    * once in the "running" state,
    * 3. creates a handler to send a message to itself in the running state when the simulation has
    * stopped or completed,
    * 4. and then, finally, switches to the running state.
    *
    * If, on the other hand, this actor receives a "destroy" command, then destroys the network.
    *
    * @param outgoingMessageActor The web-socket actor passed from the uninitialized state.
    * @return a receive instance
    */
  def built(outgoingMessageActor: ActorRef): Receive = {
    case IncomingMessage(text) => text.parseJson.convertTo match {
      case NetworkCommand("start") =>
        log.info(s"starting network; id: $id; kafka-settings: $kafkaSettings")

        // todo send a message to the network to start the simulation

        // todo the topic has to be dynamic; spikes-1 is just for testing, keys are just for testing
        val consumer: (Consumer.Control, Future[Done]) = Consumer
          .plainSource(consumerSettings(id, kafkaSettings), Subscriptions.topics("spikes-1"))
          .filter(record => record.key() == "fire")
          .toMat(Sink.foreach(record => self ! SendRecord(record)))(Keep.both)
          .run()

        // set up a handler for when the simulation is completed and has stopped
        consumer._2.onComplete(_ => self ! SimulationStopped())

        // transition to the running state
        context.become(running(outgoingMessageActor, System.currentTimeMillis(), consumer._1))

      case NetworkCommand("destroy") =>
        log.info(s"destroying network; id: $id")
        outgoingMessageActor ! PoisonPill

      case NetworkCommand(command) => log.error(s"(built) Invalid network command; command: $command")
    }
    case _ => log.error(s"Invalid incoming message type")
  }

  /**
    * In this state, the network is running.
    *
    * @param outgoingMessageActor The web-socket actor to which to send the messages
    * @param startTime            The start time of the simulation (i.e. when the network transitioned to this state
    * @param consumerControl A tuple holding the consumer control, which can be used to stop the kafka consumer, and a future
    *                 that is completed once the simulation is stopped or completed
    * @return a receive instance
    */
  def running(outgoingMessageActor: ActorRef,
              startTime: Long,
              consumerControl: Consumer.Control
             ): Receive = {

    case SendRecord(record) =>
      log.info(s"sending record: ${record.value().toString}")
      outgoingMessageActor ! OutgoingMessage(record.value().toString)

    case SimulationStopped() =>
      log.info(s"simulation completed; id: $id")
      consumerControl.stop()

    case IncomingMessage(text) =>
      text.parseJson.convertTo match {
        case NetworkCommand("stop") =>
          log.info(s"stopping network; id: $id")
          consumerControl.stop()
          context.become(built(outgoingMessageActor))

        case NetworkCommand(command) => log.error(s"(running) Invalid network command; command: $command")
      }

    case message => log.error(s"Invalid message type; message type: ${message.getClass.getName}")
  }

//  /**
//    * get a random number of messages (i.e. some random set of neurons spike)
//    *
//    * @param numNeurons The number of neurons for which signals are sent
//    * @return a list of text-messages
//    */
//  private def messages(numNeurons: Int, startTime: Long): List[OutgoingMessage] = {
//    val fireTime = System.currentTimeMillis() - startTime
//    val neuronsFiring = Random.nextInt(numNeurons)
//    Random
//      .shuffle(Range(0, numNeurons).toList)
//      .take(neuronsFiring)
//      .map(index => OutgoingMessage(s"out-$index,$fireTime,1"))
//  }

  private def consumerSettings(networkId: String, kafkaSettings: KafkaSettings): ConsumerSettings[String, String] = {
    ConsumerSettings(kafkaConfig, new StringDeserializer, new StringDeserializer)
      .withBootstrapServers(kafkaSettings.bootstrapServers.map(server => s"${server.host}:${server.port}").mkString(","))
      .withGroupId(networkId)
      .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
  }
}

object NetworkCommander {

  sealed trait NetworkMessage

  case class Build(actor: ActorRef)

  case class IncomingMessage(text: String) extends NetworkMessage

  case class OutgoingMessage(text: String) extends NetworkMessage

  case class SendMessage() extends NetworkMessage

  case class SimulationStopped() extends NetworkMessage

  case class NetworkCommand(command: String) extends NetworkMessage

  case class SendRecord(record: ConsumerRecord[String, String])

  def props(name: String, manager: ActorRef, kafkaConfig: Config, kafkaSettings: KafkaSettings) =
    Props(new NetworkCommander(name, manager, kafkaConfig, kafkaSettings))
}
