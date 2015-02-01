package org.sandbox.akka.broadcast

import java.util.concurrent.TimeoutException

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.Stash
import akka.actor.actorRef2Scala
import akka.event.LoggingReceive
import akka.pattern.ask
import akka.pattern.pipe
import akka.routing.ActorRefRoutee
import akka.routing.RoundRobinRoutingLogic
import akka.routing.Router

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

    def receive = //waiting
      receiveWithFutures

    private def getConsumers(howManyConsumers: Int, jobId: Int): Vector[ActorRef] =
      Vector.tabulate(howManyConsumers) { i =>
        context.actorOf(Props[PayloadConsumer], s"consumer$i-$jobId")
      }

    def waiting: Receive = LoggingReceive {
      def getRouter(howManyConsumers: Int, jobId: Int): Router = {
        val consumers = getConsumers(howManyConsumers, jobId) map ActorRefRoutee
        Router(RoundRobinRoutingLogic(), consumers)
      }

      {
        case PayloadBroadcastMsg(jobId, howManyConsumers, payloads) => {
          val router = getRouter(howManyConsumers, jobId)
          val payloadIds = payloads map (_.id)
          context.become(processing(jobId, payloadIds, sender))

          payloads foreach (router.route(_, self))
        }
      }
    }

    implicit val executionContext = context.system.dispatcher

    def processing(jobId: Int, payloadIds: Seq[Int], ackActor: ActorRef,
      timeout: FiniteDuration = 3.seconds): Receive = LoggingReceive {
      def becomeWaiting(msg: Any) = {
        ackActor ! msg
        context.children foreach context.stop
        unstashAll()
        //        context.become(waiting)
        context.unbecome
      }

      def scheduleTimeout =
        context.system.scheduler.scheduleOnce(timeout, self, TimeoutExpired)

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

    def receiveWithFutures: Receive = LoggingReceive {
      case PayloadBroadcastMsg(jobId, howManyConsumers, payloads) => {
        val consumers = getConsumers(howManyConsumers, jobId)
        def nextConsumer: Iterator[ActorRef] = {
          Iterator.continually(consumers).flatten
        }

        val timeout = 3.seconds
        implicit val theTimeout = akka.util.Timeout(timeout)
        val acks: Seq[Future[Any]] =
          payloads zip Seq.fill(payloads.size)(nextConsumer.next) map {
            case (payload, consumer) => consumer ? payload
          }

          val futureMsg: Future[Any] =
            Future.sequence(acks)
              .map { _ => Done(jobId) }
              .recover {
                case _: TimeoutException => Timeout(jobId)
              }
          futureMsg pipeTo sender
      }
    }
  }

  def payloads(howMany: Int): Seq[Payload] =
    (0 until howMany).map(id => Payload(id, s"content$id"))

  val actorSystem = ActorSystem("PayloadBroadcast")
  val broadcaster = actorSystem.actorOf(Props[PayloadBroadcaster], "broadcaster")
  val sender = actorSystem.actorOf(Props(
    new Actor {
      def receive = LoggingReceive {
        case Done(42) => {
          println(s"shutting down system ...")
          actorSystem.shutdown
        }
      }
    }), "terminator")
  broadcaster.tell(PayloadBroadcastMsg(42, 5, payloads(10)), sender)

  //  system.shutdown
}
