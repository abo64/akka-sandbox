package org.sandbox.akka.counts

import java.io.IOException
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import CircuitBreakerEnabled.CircuitBreakerOpen
import CountGetter.Counter
import CountGetter.GetCounter
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.OneForOneStrategy
import akka.actor.Props
import akka.actor.Stash
import akka.actor.SupervisorStrategy
import akka.actor.actorRef2Scala
import akka.event.LoggingReceive
import akka.pattern.CircuitBreakerOpenException
import akka.routing.ActorRefRoutee
import akka.routing.RoundRobinRoutingLogic
import akka.routing.Router
import org.sandbox.akka.counts.CircuitBreakerEnabled.CircuitBreakerHalfOpen
import org.sandbox.akka.counts.CircuitBreakerEnabled.CircuitBreakerClosed

class CountRetriever(countGetterFactory: ActorRefFactory => ActorRef) extends Actor with Stash {
  import CountRetriever._
  import CountGetter._

  def receive: Receive = waiting

  private def createCountGetter: ActorRef = countGetterFactory(context)

  private var router: Option[Router] = None
  private var jobId: Option[Int] = None
  private def sendGetCounter: Unit =
    for {
      r <- router
      id <- jobId
    } r.route(GetCounter(id), self)

  private def waiting: Receive = LoggingReceive {
    def getCountGetters(howMany: Int, jobId: Int): Vector[ActorRef] =
      Vector.tabulate(howMany)(_ => createCountGetter)
    def getRouter(howMany: Int, jobId: Int): Router = {
      val countGetters = getCountGetters(howMany, jobId) map ActorRefRoutee
      Router(RoundRobinRoutingLogic(), countGetters)
    }

    {
      case GetCounters(jobId, howMany, timeout) =>
        router = Some(getRouter(howMany min 20, jobId))
        this.jobId = Some(jobId)
        context.become(collectCounts(howMany, jobId, sender, timeout))
        (1 to howMany) foreach (_ => sendGetCounter)
    }
  }

  private def collectCounts(howMany: Int, jobId: Int, requestor: ActorRef,
      timeout: FiniteDuration): Receive = {
    def becomeWaiting = {
      context.children foreach context.stop
      this.router = None
      this.jobId = None
      unstashAll
      context.unbecome
    }

    def scheduleTimeout = {
      implicit val executionContext = context.system.dispatcher
      context.system.scheduler.scheduleOnce(timeout, self, TimeoutExpired(jobId, timeout))
    }

    def handleTimeout: Receive = {
      case te@TimeoutExpired(id, _) if id == jobId =>
        becomeWaiting
        requestor ! te
    }

    def handleCircuitBreaker: Receive = {
      def addRoutee(routee: ActorRef): Unit = {
        val addRoutee =
          router map (_.routees.contains(ActorRefRoutee(routee))) getOrElse (false)
        if (addRoutee)
          router = router map (_.addRoutee(routee))
      }
      def removeRoutee(routee: ActorRef): Unit =
        router = router map (_.removeRoutee(routee))

      {
        case CircuitBreakerOpen(countGetter, howLong) => removeRoutee(countGetter)
        case CircuitBreakerHalfOpen(countGetter) => addRoutee(countGetter)
        case CircuitBreakerClosed(countGetter) => addRoutee(countGetter)
      }
    }

    var counters = Set.empty[Int]
    def collect: Receive = LoggingReceive {
      case Counter(id, counter) if id == jobId =>
        counters += counter
        if (counters.size == howMany) {
          requestor ! Counters(jobId, counters)
          becomeWaiting
        }
      case _: GetCounters => stash
    }

    scheduleTimeout
    collect orElse handleTimeout orElse handleCircuitBreaker
  }

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1 second) {
      case _: IOException =>
        sendGetCounter // try again
        SupervisorStrategy.Restart
      case e: CircuitBreakerOpenException =>
        SupervisorStrategy.Resume
      case _ => SupervisorStrategy.Stop
    }
}

object CountRetriever {
  def props(countGetterFactory: ActorRefFactory => ActorRef): Props =
    Props(new CountRetriever(countGetterFactory))

  sealed trait CountRetrieverMsg
  case class GetCounters(jobId: Int, howMany: Int, timeout: FiniteDuration = 5 seconds) extends CountRetrieverMsg
  case class Counters(jobId: Int, counters: Set[Int]) extends CountRetrieverMsg
  case class TimeoutExpired(jobId: Int, timeout: FiniteDuration) extends CountRetrieverMsg
}
