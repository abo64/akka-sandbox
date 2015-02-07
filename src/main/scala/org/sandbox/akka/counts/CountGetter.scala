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

class CountGetter(countProvider: CountProvider) extends Actor with CircuitBreakerEnabled {
  import CountGetter._
  import CircuitBreakerEnabled._

  import context.dispatcher

  override def receive = LoggingReceive {
    case GetCounter(jobId) =>
      val nextCounter = withSyncCircuitBreaker(countProvider.getNext)
      sender ! Counter(jobId, nextCounter)
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
}
