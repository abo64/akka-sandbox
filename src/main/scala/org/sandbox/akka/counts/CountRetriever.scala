package org.sandbox.akka.counts

import CountGetter.GetCounter
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.event.LoggingReceive

class CountRetriever(countGetterFactory: ActorRefFactory => ActorRef) extends Actor {
  import CountRetriever._
  import CountGetter._

  def receive: Receive = waiting

  private def waiting: Receive = LoggingReceive {
    case GetCounters(jobId, howMany) =>
      countGetterFactory(context) ! GetCounter(jobId)
      context.become(collectCounts(sender))
  }

  private def collectCounts(requestor: ActorRef): Receive = LoggingReceive {
    case Counter(jobId, counter) =>
      requestor ! Counters(jobId, Seq(counter))
      context.unbecome
    
  }
}

object CountRetriever {
  def props(countGetterFactory: ActorRefFactory => ActorRef): Props =
    Props(new CountRetriever(countGetterFactory))

  sealed trait CountRetrieverMsg
  case class GetCounters(jobId: Int, howMany: Int) extends CountRetrieverMsg
  case class Counters(jobId: Int, counters: Seq[Int]) extends CountRetrieverMsg
}
