package org.sandbox.akka.counts

import java.io.IOException

import scala.annotation.implicitNotFound
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import org.sandbox.akka.counts.CircuitBreakerEnabled.CircuitBreakerClosed
import org.sandbox.akka.counts.CircuitBreakerEnabled.CircuitBreakerHalfOpen

import CircuitBreakerEnabled.CircuitBreakerOpen
import CountGetter.Counter
import CountGetter.GetCounter
import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.OneForOneStrategy
import akka.actor.Props
import akka.actor.Stash
import akka.actor.SupervisorStrategy
import akka.actor.actorRef2Scala
import akka.event.LoggingReceive
import akka.pattern.CircuitBreakerOpenException
import akka.persistence.PersistentActor
import akka.routing.ActorRefRoutee
import akka.routing.RoundRobinRoutingLogic
import akka.routing.Router

class CountRetriever(countGetterFactory: ActorRefFactory => ActorRef, val persistenceId: String)
  extends PersistentActor with Stash
{
  import CountRetriever._
  import CountGetter._

  override def receiveCommand: Receive = waiting

  case class JobResult(jobId: Int, howMany: Int, requester: ActorRef, counters: Set[Int] = Set()) {
    def updated(counter: Counter): JobResult =
      if (counter.jobId == jobId) copy(counters = counters + counter.counter) else this
    def isComplete = counters.size >= howMany
  }

  var jobResult: Option[JobResult] = None

  def handleCounter: Receive = {
    def process(counter: Counter) = {
      jobResult = jobResult map (_.updated(counter))
      jobResult foreach { jr =>
        if (jr.isComplete) {
          jr.requester ! Counters(jr.jobId, jr.counters)
          becomeWaiting
        }
      }
    }

    { case counter: Counter => persist(counter)(process) }
  }

  def becomeWaiting = {
    context.children foreach context.stop
    router = None
    jobResult = None
    deleteMessages(Long.MaxValue)
    unstashAll
    context.unbecome
  }

  override def receiveRecover: Receive = {
    case _ =>
  }

  private def createCountGetter: ActorRef = countGetterFactory(context)

  private var router: Option[Router] = None

  def initRouter(jobId: Int, howMany: Int): Unit = {
    def getCountGetters: Vector[ActorRef] =
      Vector.tabulate(howMany min 20)(_ => createCountGetter)
    val countGetters = getCountGetters map ActorRefRoutee
    router = Some(Router(RoundRobinRoutingLogic(), countGetters))
  }

  private def sendGetCounter: Unit =
    for {
      r <- router
      jr <- jobResult
    } r.route(GetCounter(jr.jobId), self)

  private def waiting: Receive = LoggingReceive {
    {
      case GetCounters(jobId, howMany, timeout) =>
        initRouter(jobId, howMany)
        jobResult = Some(JobResult(jobId, howMany, sender))
        context.become(collectCounts(jobId, sender, timeout))
        (1 to howMany) foreach (_ => sendGetCounter)
    }
  }

  private def collectCounts(jobId: Int, requester: ActorRef,
      timeout: FiniteDuration): Receive =
  {
    def scheduleTimeout = {
      implicit val executionContext = context.system.dispatcher
      context.system.scheduler.scheduleOnce(timeout, self, TimeoutExpired(jobId, timeout))
    }

    def handleTimeout: Receive = {
      case te@TimeoutExpired(id, _) if id == jobId =>
        becomeWaiting
        requester ! te
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

    def collect: Receive = LoggingReceive {
      handleCounter orElse stashIt
    }

    def stashIt: Receive = {
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
  def props(countGetterFactory: ActorRefFactory => ActorRef, persistenceId: String): Props =
    Props(new CountRetriever(countGetterFactory, persistenceId))

  sealed trait CountRetrieverMsg
  case class GetCounters(jobId: Int, howMany: Int, timeout: FiniteDuration = 5 seconds) extends CountRetrieverMsg
  case class Counters(jobId: Int, counters: Set[Int]) extends CountRetrieverMsg
  case class TimeoutExpired(jobId: Int, timeout: FiniteDuration) extends CountRetrieverMsg
}
