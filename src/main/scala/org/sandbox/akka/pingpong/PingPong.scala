package org.sandbox.akka.pingpong

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration.DurationInt

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.Terminated
import akka.actor.actorRef2Scala
import akka.event.LoggingReceive

class Bouncer(bounce: (Any, ActorRef) => Boolean) extends Actor with ActorLogging {
  implicit val ec = context.dispatcher
  def scheduleBounce(msg: Any) =
    // avoid blocking: schedule instead of Thread.sleep()!
    context.system.scheduler.scheduleOnce(1.second, sender, msg)

  override def postStop = log.info(s"${self.path.name} stopped!")

  def receive = LoggingReceive {
    case msg => if (bounce(msg, self)) scheduleBounce(msg)
  }
}

case object Ball

object PingPong extends App {
  val totalBounces = 5
  val bounces = new AtomicInteger(0)

  def bounce(name: String)(msg: Any, self: ActorRef): Boolean = {
    if (msg == Ball) {
      val newBounces = bounces.incrementAndGet
      val bounceBack =
        //newBounces < totalBounces
        scala.util.Random.nextDouble < 0.5
      println(s"$newBounces $name: received $msg => ${if (bounceBack) "Hit" else "Miss"}!")
      if (!bounceBack) self ! PoisonPill
      bounceBack
    }
    else false
  }
  val system = ActorSystem("PingPong")
  val ping = system.actorOf(Props(new Bouncer(bounce("ping"))), name = "ping")
  val pong = system.actorOf(Props(new Bouncer(bounce("pong"))), name = "pong")

  val deathWatcher =
    system.actorOf(Props(new DeathWatcher(Set(ping, pong), _ => system.shutdown)), name = "deathWatcher")

  ping.tell("ignoredMsg", pong)
  ping.tell(Ball, pong)
}

class DeathWatcher(toWatch: Set[ActorRef], onTermination: ActorRef => Unit) extends Actor {
  toWatch foreach context.watch

  def receive = {
    case Terminated(terminated) => onTermination(terminated)
  }

  override def postStop = println(s"${self.path.name} stopped")
//  override def postStop = toWatch foreach context.unwatch
}