package org.sandbox.akka.pingpong

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.Try

import com.typesafe.config.ConfigFactory

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.AddressFromURIString
import akka.actor.Deploy
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.Terminated
import akka.actor.actorRef2Scala
import akka.event.LoggingReceive
import akka.remote.RemoteScope

class Bouncer[T](bounce: (T, ActorRef) => Option[T]) extends Actor with ActorLogging {
  implicit val ec = context.dispatcher
  def scheduleBounce(msg: T) =
    // avoid blocking: schedule instead of Thread.sleep()!
    context.system.scheduler.scheduleOnce(1.second, sender, msg)

  override def preStart = log.info(s"${self.path} starting ...")
  override def postStop = log.info(s"${self.path} stopped!")

  def receive = LoggingReceive {
    case msg: T => bounce(msg, self) foreach scheduleBounce
  }
}

case class Ball(bounces: Int)

object PingPong extends App {
  val totalBounces = 5

  def bounce(bounceProbability: Double)(msg: Any, self: ActorRef): Option[Any] = msg match {
    case Ball(bounces) => {
//      val newBounces = bounces + 1
      val bounceBack =
        //newBounces < totalBounces
        scala.util.Random.nextDouble < bounceProbability
      println(s"${self.path.name}: received $msg => ${if (bounceBack) "Hit" else "Miss"}!")
      if (!bounceBack) {
        self ! PoisonPill
        None
      } else Some(Ball(bounces + 1))
    }
    case _ => None
  }

  val system = ActorSystem("PingPong")

  def resolveRemotePong: ActorRef = {
    val pongF =
      system.actorSelection("akka.tcp://Pong@127.0.0.1:2552/user/pong").resolveOne(1.second)
    Await.result(pongF, 1.second)
  }

  def resolveOrCreateRemotePong: ActorRef = {
    def createRemotePong: ActorRef = {
      val address = AddressFromURIString("akka.tcp://Pong@127.0.0.1:2552")
      system.actorOf(
        Props(new Bouncer(bounce(1))).withDeploy(Deploy(scope = RemoteScope(address))),
        name = "pong")
    }
    Try(resolveRemotePong) getOrElse createRemotePong
  }

  val ping = system.actorOf(Props(new Bouncer(bounce(0.8))), name = "ping")
  val pong = //system.actorOf(Props(new Bouncer(bounce("pong"))), name = "pong")
    resolveOrCreateRemotePong

  val deathWatcher =
    system.actorOf(
        Props(new DeathWatcher(Set(ping), _ => {
          println(s"shutting down ActorSystem ${system.name} ...")
          system.shutdown
        })),
        name = "deathWatcher")

  ping.tell("ignoredMsg", ActorRef.noSender)
  ping.tell(Ball(0), pong)
}

object Pong extends App {
  val config =
    ConfigFactory.parseString("akka.remote.netty.tcp.port=2552").withFallback(ConfigFactory.load)
  val system = ActorSystem("Pong", config)
//  val pong = system.actorOf(Props(new Bouncer(PingPong.bounce(1))), name = "pong")
}

class DeathWatcher(toWatch: Set[ActorRef], onTermination: ActorRef => Unit) extends Actor with ActorLogging {
  toWatch foreach context.watch

  def receive = {
    case Terminated(terminated) => onTermination(terminated)
  }

  override def postStop = log.info(s"${self.path.name} stopped")
//  override def postStop = toWatch foreach context.unwatch
}