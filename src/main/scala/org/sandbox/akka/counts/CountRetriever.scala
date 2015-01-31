package org.sandbox.akka.counts

import CountGetter.Counter
import CountGetter.GetCounter
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.event.LoggingReceive
import akka.routing.ActorRefRoutee
import akka.routing.RoundRobinRoutingLogic
import akka.routing.Router

class CountRetriever(countGetterFactory: ActorRefFactory => ActorRef) extends Actor {
  import CountRetriever._
  import CountGetter._

  def receive: Receive = waiting

  private def waiting: Receive = LoggingReceive {
    def getCountGetters(howMany: Int, jobId: Int): Vector[ActorRef] =
      Vector.tabulate(howMany)(_ => countGetterFactory(context))
    def getRouter(howMany: Int, jobId: Int): Router = {
      val countGetters = getCountGetters(howMany, jobId) map ActorRefRoutee
      Router(RoundRobinRoutingLogic(), countGetters)
    }

    {
      case GetCounters(jobId, howMany) =>
        val router = getRouter(howMany max 20, jobId)
        context.become(collectCounts(howMany, sender))
        (1 to howMany) foreach (_ => router.route(GetCounter(jobId), self))
    }
  }

  private def collectCounts(howMany: Int, requestor: ActorRef): Receive = {
    var counters = Set.empty[Int]
    def collect: Receive = LoggingReceive {
      case Counter(jobId, counter) =>
      counters += counter
      if (counters.size == howMany) {
        requestor ! Counters(jobId, counters)
        context.children foreach context.stop
        context.unbecome
      }
    }

    collect
  }
}

object CountRetriever {
  def props(countGetterFactory: ActorRefFactory => ActorRef): Props =
    Props(new CountRetriever(countGetterFactory))

  sealed trait CountRetrieverMsg
  case class GetCounters(jobId: Int, howMany: Int) extends CountRetrieverMsg
  case class Counters(jobId: Int, counters: Set[Int]) extends CountRetrieverMsg
}
