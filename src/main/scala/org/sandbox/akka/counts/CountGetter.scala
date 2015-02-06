package org.sandbox.akka.counts

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.event.LoggingReceive
import akka.pattern.CircuitBreaker
import akka.pattern.CircuitBreakerOpenException

class CountGetter(countProvider: CountProvider) extends Actor {
  import CountGetter._

  import context.dispatcher

  val circuitBreaker =
    new CircuitBreaker(context.system.scheduler,
      maxFailures = 5,
      callTimeout = 10 seconds,
      resetTimeout = 1 minute)
        .onClose(onClose)

  def onClose: Unit = {
    openCircuitBreakerCalls foreach (_ ! CircuitBreakerClosed(self))
    openCircuitBreakerCalls = Set()
  }

  var openCircuitBreakerCalls: Set[ActorRef] = Set()

  override def receive = LoggingReceive {
    case GetCounter(jobId) =>
      try {
        val nextCounter = circuitBreaker.withSyncCircuitBreaker(countProvider.getNext)
        sender ! Counter(jobId, nextCounter)
      } catch {
        case e: CircuitBreakerOpenException =>
          openCircuitBreakerCalls += sender
          sender ! CircuitBreakerOpen(self, e.remainingDuration)
          throw e // supervisor is still in charge here
      }
//      val nextCounter = circuitBreaker.withCircuitBreaker(Future(countProvider.getNext))
//      val counterMsg = nextCounter map(counter => (sender, Counter(jobId, counter)))
//      counterMsg pipeTo self
//    case (requester: ActorRef, counterMsg: Counter) => requester ! counterMsg
//    case Failure(e) => throw e // so that the supervisor can handle it
  }
}

object CountGetter {
  def props(countProvider: CountProvider): Props =
    Props(new CountGetter(countProvider))

  sealed trait CountGetterMsg
  case class GetCounter(jobId: Int) extends CountGetterMsg
  case class Counter(jobId: Int, counter: Int) extends CountGetterMsg
  case class CircuitBreakerOpen(countGetter: ActorRef, howLong: FiniteDuration)
  case class CircuitBreakerClosed(countGetter: ActorRef)
}
