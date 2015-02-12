package org.sandbox.akka.counts

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.actorRef2Scala
import akka.pattern.CircuitBreaker
import akka.pattern.CircuitBreakerOpenException

trait CircuitBreakerEnabled extends Actor {

  import CircuitBreakerEnabled._
  import context.dispatcher

  private val circuitBreaker =
    new CircuitBreaker(context.system.scheduler,
      maxFailures = 5,
      callTimeout = 10 seconds,
      resetTimeout = 1 minute)
        .onClose(onClose)
        .onHalfOpen(onHalfOpen)

  private var openCircuitBreakerCalls: Set[ActorRef] = Set()

  private def onClose: Unit = {
    openCircuitBreakerCalls foreach (_ ! CircuitBreakerClosed(self))
    openCircuitBreakerCalls = Set()
  }

  private def onHalfOpen: Unit = {
    openCircuitBreakerCalls foreach (_ ! CircuitBreakerHalfOpen(self))
  }

  def withSyncCircuitBreaker[T](body: ⇒ T): T =
      try {
        circuitBreaker.withSyncCircuitBreaker(body)
      } catch {
        case e: CircuitBreakerOpenException =>
          openCircuitBreakerCalls += sender
          sender ! CircuitBreakerOpen(self, e.remainingDuration)
          throw e // supervisor is still in charge here
      }

}

object CircuitBreakerEnabled {
  sealed trait CircuitBreakerEnabledMsg
  case class CircuitBreakerOpen(countGetter: ActorRef, howLong: FiniteDuration) extends CircuitBreakerEnabledMsg
  case class CircuitBreakerClosed(countGetter: ActorRef) extends CircuitBreakerEnabledMsg
  case class CircuitBreakerHalfOpen(countGetter: ActorRef) extends CircuitBreakerEnabledMsg
}
