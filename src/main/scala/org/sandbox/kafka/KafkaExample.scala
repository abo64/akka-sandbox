package org.sandbox.kafka

import akka.actor.{Props, ActorSystem, Actor}
import com.sclasen.akka.kafka.{AkkaConsumer, AkkaConsumerProps, StreamFSM}
import kafka.serializer.DefaultDecoder
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

object KafkaExample extends App {

  class Printer extends Actor{
    def receive = {
      case x:Any =>
        println(x)
        sender ! StreamFSM.Processed
    }
  }

  val system = ActorSystem("test")
  val printer = system.actorOf(Props[Printer])


  /*
  the consumer will have 4 streams and max 64 messages per stream in flight, for a total of 256
  concurrently processed messages.
  */
  val consumerProps = AkkaConsumerProps.forSystem(
    system = system,
    zkConnect = "localhost:2181",
    topic = "achims-topic",
    group = "achims-consumer-group",
    streams = 4, //one per partition
    keyDecoder = new DefaultDecoder(),
    msgDecoder = new DefaultDecoder(),
    receiver = printer
  )

  val consumer = new AkkaConsumer(consumerProps)

  val s = consumer.start()  //returns a Future[Unit] that completes when the connector is started
  Await.ready(s, 2 seconds)

  val c = consumer.commit() //returns a Future[Unit] that completes when all in-flight messages are processed and offsets are committed.
  Await.ready(c, 2 seconds)

  val st = consumer.stop()   //returns a Future[Unit] that completes when the connector is stopped.
  Await.ready(st, 2 seconds)
  
  system.shutdown
}