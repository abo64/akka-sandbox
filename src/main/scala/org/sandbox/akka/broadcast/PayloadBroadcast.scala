package org.sandbox.akka.broadcast

import akka.actor.Actor
import akka.actor.Props
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.ActorLogging

object PayloadBroadcast extends App {
  type PayloadId = Int
  case class Payload(id: PayloadId, content: Any)
  case class PayloadBroadcast(howMany: Int, payloads: Seq[Payload])
  case class PayloadAck(id: PayloadId)
  case object Done
  case class Payloads(payloads: Seq[Payload], chunkSize: Int)

  class PayloadConsumer extends Actor with ActorLogging {
    def receive = {
      case pl@Payload(id, _) =>
        log.info(s"${self.path.name} received: $pl")
        sender ! PayloadAck(id)
    }
  }

  class PayloadBroadcaster extends Actor {
    def receive = waiting

    def waiting: Receive = {
      case PayloadBroadcast(howMany, payloads) => {
        // TODO only create howMany consumers
        (payloads zipWithIndex) foreach {
          case (payload, i) =>
            val consumer = context.actorOf(Props[PayloadConsumer], s"consumer$i")
            consumer ! payload
        }
        val ids = payloads map (_.id)
        idsToProcess = ids
        context.become(processing(ids, sender))
      }
    }

    var idsToProcess: Seq[Int] = _

    def processing(ids: Seq[Int], ackActor: ActorRef): Receive = {
      case PayloadAck(id) => {
        idsToProcess = idsToProcess.diff(Seq(id))
        if (idsToProcess.isEmpty) {
          ackActor ! Done
          context.children foreach context.stop
          context.become(waiting)
        }
      }
    }
  }

  def payloads(howMany: Int): Seq[Payload] =
    Stream.range(0, howMany).map(id => Payload(id, Payload(id, s"content$id")))

  val system = ActorSystem("PayloadBroadcast")
  val broadcaster = system.actorOf(Props[PayloadBroadcaster], "broadcaster")
  val sender = system.actorOf(Props(
      new Actor {
        def receive = {
          case msg => {
            println(s"received $msg")
            system.shutdown
          }
        }
      }), "testSender")
  broadcaster.tell(PayloadBroadcast(5, payloads(5)), sender)

//  system.shutdown
}
