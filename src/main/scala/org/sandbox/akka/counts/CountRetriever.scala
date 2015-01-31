package org.sandbox.akka.counts

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.Props

class CountRetriever(countGetterFactory: ActorRefFactory => ActorRef) extends Actor {
  import CountRetriever._

  def receive: Receive = {
    case GetCounters(howMany) => ???
  }
}

object CountRetriever {
  def props(countGetterFactory: ActorRefFactory => ActorRef): Props =
    Props(new CountRetriever(countGetterFactory))

  sealed trait CountRetrieverMsg
  case class GetCounters(howMany: Int) extends CountRetrieverMsg
  case class Counters(counters: Set[Int]) extends CountRetrieverMsg
}
