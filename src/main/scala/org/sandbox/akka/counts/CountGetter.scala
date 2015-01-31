package org.sandbox.akka.counts

import akka.actor.Actor
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.event.LoggingReceive

class CountGetter(countProvider: CountProvider) extends Actor {
  import CountGetter._

  override def receive = LoggingReceive {
    case GetCounter(jobId) => sender ! Counter(jobId, countProvider.getNext)
  }
}

object CountGetter {
  def props(countProvider: CountProvider): Props =
    Props(new CountGetter(countProvider))

  sealed trait CountGetterMsg
  case class GetCounter(jobId: Int) extends CountGetterMsg
  case class Counter(jobId: Int, counter: Int) extends CountGetterMsg
}
