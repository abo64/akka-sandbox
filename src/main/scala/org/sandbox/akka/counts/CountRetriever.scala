package org.sandbox.akka.counts

import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import CountGetter.Counter
import CountGetter.GetCounter
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.Props
import akka.actor.Stash
import akka.actor.actorRef2Scala
import akka.event.LoggingReceive
import akka.routing.ActorRefRoutee
import akka.routing.RoundRobinRoutingLogic
import akka.routing.Router
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeoutException

class CountRetriever(countGetterFactory: ActorRefFactory => ActorRef) extends Actor with Stash {
  import CountRetriever._
  import CountGetter._

  def receive: Receive = waiting

  private def createCountGetter: ActorRef = countGetterFactory(context)

  private def waiting: Receive = LoggingReceive {
    def getCountGetters(howMany: Int, jobId: Int): Vector[ActorRef] =
      Vector.tabulate(howMany)(_ => createCountGetter)
    def getRouter(howMany: Int, jobId: Int): Router = {
      val countGetters = getCountGetters(howMany, jobId) map ActorRefRoutee
      Router(RoundRobinRoutingLogic(), countGetters)
    }

    {
      case GetCounters(jobId, howMany, timeout) =>
        val router = getRouter(howMany max 20, jobId)
        context.become(collectCounts(howMany, jobId, sender, timeout))
        (1 to howMany) foreach (_ => router.route(GetCounter(jobId), self))
    }
  }

  private def collectCounts(howMany: Int, jobId: Int, requestor: ActorRef, timeout: FiniteDuration): Receive = {
    def becomeWaiting = {
      context.children foreach context.stop
      unstashAll
      context.unbecome
    }

    implicit val executionContext = context.system.dispatcher
    def scheduleTimeout =
      context.system.scheduler.scheduleOnce(timeout, self, TimeoutExpired(jobId))

    var counters = Set.empty[Int]
    def collect: Receive = LoggingReceive {
      case Counter(id, counter) if id == jobId =>
        counters += counter
        if (counters.size == howMany) {
          requestor ! Counters(jobId, counters)
          becomeWaiting
        }
      case _: GetCounters => stash
      case TimeoutExpired(id) if id == jobId =>
        becomeWaiting
        throw new TimeoutException(s"jobId=$jobId: timeout after $timeout")
    }

    scheduleTimeout
    collect
  }
}

object CountRetriever {
  def props(countGetterFactory: ActorRefFactory => ActorRef): Props =
    Props(new CountRetriever(countGetterFactory))

  sealed trait CountRetrieverMsg
  case class GetCounters(jobId: Int, howMany: Int, timeout: FiniteDuration = 5 seconds) extends CountRetrieverMsg
  case class Counters(jobId: Int, counters: Set[Int]) extends CountRetrieverMsg
  case class TimeoutExpired(jobId: Int)
}
