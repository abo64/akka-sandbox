package org.sandbox.akka.counts

import akka.actor.Actor
import akka.actor.Props

class CountGetter(countProvider: CountProvider) extends Actor {
  import CountGetter._

  override def receive = {
    case GetCounter => sender ! Counter(countProvider.getNext)
  }
}

object CountGetter {
  def props(countProvider: CountProvider): Props =
    Props(new CountGetter(countProvider))

  sealed trait CountGetterMsg
  case object GetCounter extends CountGetterMsg
  case class Counter(counter: Int) extends CountGetterMsg
}
