package org.sandbox.akka.pingpong

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorNotFound
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
import scala.reflect.ClassTag

class Bouncer[T : ClassTag](bounce: (T, ActorRef) => Option[T]) extends Actor with ActorLogging {
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

case class Ball(hits: Int)

object PingPong extends App {
  def bounce(hitProbability: Double)(msg: Any, self: ActorRef): Option[Any] = msg match {
    case Ball(hits) => {
      val hitBall =
        scala.util.Random.nextDouble < hitProbability
      println(s"${self.path.name}: received $msg => ${if (hitBall) "Hit" else "Miss"}!")
      if (!hitBall) {
        self ! PoisonPill
        None
      } else Some(Ball(hits + 1))
    }
    case _ => None
  }

  val remoteConfig = {
    val config = ConfigFactory.load()
    config.getConfig("with-remote").withFallback(config)
  }
  val system = ActorSystem("PingPong", remoteConfig)
  implicit val executionContenxt = system.dispatcher

  def resolveOrCreateRemotePong: ActorRef = {
    val uriStr = "akka.tcp://Pong@127.0.0.1:2552"

    def createRemotePong: ActorRef = {
      println("creating pong actor ...")
      val address = AddressFromURIString(uriStr)
      val pong =
        // note that as the actor is created from system it will be stopped when system shuts down!
        system.actorOf(
          Props(new Bouncer(bounce(1))).withDeploy(Deploy(scope = RemoteScope(address))),
          name = "pong")
      println(s"created $pong")
      pong
    }

    def resolveRemotePong: Future[ActorRef] = {
      val pong =
        system.actorSelection(s"$uriStr/user/pong").resolveOne(1.second)
      pong onSuccess { case p => println(s"found $p") }
      pong
    }

    val pong = resolveRemotePong recover { case _: ActorNotFound  => createRemotePong }
    Await.result(pong, 1.second)
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
//  pong onSuccess { case p => ping.tell(Ball(0), p) }
//  Await.ready(pong, 1.second)
}

object Pong extends App {
  val remoteConfig = {
    val config = ConfigFactory.load()
    config.getConfig("with-remote").withFallback(config)
  }
  val port = 2552
  val config =
    ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port").withFallback(remoteConfig)
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
