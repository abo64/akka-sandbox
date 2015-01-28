package org.sandbox.akka.broadcast

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.Stash
import akka.actor.actorRef2Scala
import akka.event.LoggingReceive
import akka.routing.ActorRefRoutee
import akka.routing.RoundRobinRoutingLogic
import akka.routing.Router
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.DurationInt

object PayloadBroadcast extends App {
  type PayloadId = Int
  case class Payload(id: PayloadId, content: Any)
  case class PayloadBroadcastMsg(jobId: Int, howManyConsumers: Int, payloads: Seq[Payload])
  case class PayloadAck(id: PayloadId)
  case class Done(jobId: Int)
  case class Timeout(jobId: Int)
  //  case class Payloads(payloads: Seq[Payload], chunkSize: Int)
  case object TimeoutExpired

  //  implicit val 
  class PayloadConsumer extends Actor with ActorLogging {
    def receive = LoggingReceive {
      case Payload(id, _) => sender ! PayloadAck(id)
    }
  }

  class PayloadBroadcaster extends Actor with Stash {
    def receive = waiting

    def waiting: Receive = LoggingReceive {
      case PayloadBroadcastMsg(jobId, howManyConsumers, payloads) => {
        val consumers =
          Vector.tabulate(howManyConsumers) { i =>
            val consumer = context.actorOf(Props[PayloadConsumer], s"consumer$i-$jobId")
            ActorRefRoutee(consumer)
          }
        val router = Router(RoundRobinRoutingLogic(), consumers)
        val payloadIds = payloads map (_.id)
        context.become(processing(jobId, payloadIds, sender))

        payloads foreach (router.route(_, self))
      }
    }

    def processing(jobId: Int, payloadIds: Seq[Int], ackActor: ActorRef,
      timeout: FiniteDuration = 3.seconds): Receive = LoggingReceive
    {
      def becomeWaiting(msg: Any) = {
        ackActor ! msg
        context.children foreach context.stop
        unstashAll()
//        context.become(waiting)
        context.unbecome
      }

      def scheduleTimeout = {
        Option(system) foreach { system =>
          import system.dispatcher // use the system's dispatcher as ExecutionContext
          system.scheduler.scheduleOnce(timeout) { self ! TimeoutExpired }
        }
      }

      var idsToProcess: Seq[Int] = payloadIds

      def process: Receive = {
        case PayloadAck(id) => {
          idsToProcess = idsToProcess.diff(Seq(id))
          if (idsToProcess.isEmpty) becomeWaiting(Done(jobId))
        }
        case TimeoutExpired => becomeWaiting(Timeout(jobId))
        case _: PayloadBroadcastMsg => stash()
      }

      scheduleTimeout
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
        case Done(42) => {
          println(s"shutting down system ...")
          system.shutdown
        }
      }
    }), "terminator")
  broadcaster.tell(PayloadBroadcastMsg(42, 5, payloads(10)), sender)

  //  system.shutdown
}
