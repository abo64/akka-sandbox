package org.sandbox.akka.broadcast

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.event.LoggingReceive
import akka.routing.ActorRefRoutee
import akka.routing.RoundRobinRoutingLogic
import akka.routing.Router

object PayloadBroadcast extends App {
  type PayloadId = Int
  case class Payload(id: PayloadId, content: Any)
  case class PayloadBroadcast(howMany: Int, payloads: Seq[Payload])
  case class PayloadAck(id: PayloadId)
  case object Done
  case class Payloads(payloads: Seq[Payload], chunkSize: Int)

  class PayloadConsumer extends Actor with ActorLogging {
    def receive = LoggingReceive {
      case Payload(id, _) => sender ! PayloadAck(id)
    }
  }

  class PayloadBroadcaster extends Actor {
    def receive = waiting

    def waiting: Receive = LoggingReceive {
      case PayloadBroadcast(howManyConsumers, payloads) => {
        val consumers =
          Vector.tabulate(howManyConsumers){ i =>
          val consumer = context.actorOf(Props[PayloadConsumer], s"consumer$i")
          ActorRefRoutee(consumer)
        }
        val router = Router(RoundRobinRoutingLogic(), consumers)
        val ids = payloads map (_.id)
        context.become(processing(ids, sender))

        payloads foreach(router.route(_, self))
      }
    }

    def processing(ids: Seq[Int], ackActor: ActorRef): Receive = LoggingReceive {
      var idsToProcess: Seq[Int] = ids
      def process: Receive = {
        case PayloadAck(id) => {
          idsToProcess = idsToProcess.diff(Seq(id))
          if (idsToProcess.isEmpty) {
            ackActor ! Done
            context.children foreach context.stop
            context.become(waiting)
          }
        }
      }
      process
    }
  }

  def payloads(howMany: Int): Seq[Payload] =
    Stream.range(0, howMany).map(id => Payload(id, s"content$id"))

  val system = ActorSystem("PayloadBroadcast")
  val broadcaster = system.actorOf(Props[PayloadBroadcaster], "broadcaster")
  val sender = system.actorOf(Props(
      new Actor {
        def receive = LoggingReceive {
          case Done => {
            println(s"shutting down system ...")
            system.shutdown
          }
        }
      }), "terminator")
  broadcaster.tell(PayloadBroadcast(5, payloads(10)), sender)

//  system.shutdown
}
