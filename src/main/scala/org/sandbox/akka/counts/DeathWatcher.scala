package org.sandbox.akka.counts

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Terminated
import akka.actor.Props

class DeathWatcher(subject: ActorRef, onTermination: ActorRef => Unit) extends Actor {
  context.watch(subject)

  override def receive: Receive = {
    case Terminated(terminated) => onTermination(terminated)
  }
}

object DeathWatcher {
  def props(subject: ActorRef, onTermination: ActorRef => Unit): Props =
    Props(new DeathWatcher(subject, onTermination))
}
