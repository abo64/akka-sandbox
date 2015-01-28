package org.sandbox.akka.broadcast

import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.sandbox.akka.broadcast.PayloadBroadcast.Done
import org.sandbox.akka.broadcast.PayloadBroadcast.PayloadBroadcastMsg
import org.sandbox.akka.broadcast.PayloadBroadcast.PayloadBroadcaster
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Finders
import org.scalatest.FlatSpecLike
import org.scalatest.Matchers

import com.typesafe.config.ConfigFactory

import PayloadBroadcast.Payload
import PayloadBroadcast.PayloadAck
import PayloadBroadcast.PayloadConsumer
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.testkit.DefaultTimeout
import akka.testkit.ImplicitSender
import akka.testkit.TestKit

@RunWith(classOf[org.scalatest.junit.JUnitRunner])
class PayloadBroadcastSpec
  extends TestKit(ActorSystem("PayloadBroadcastSpec", ConfigFactory.parseString("akka.loglevel=WARNING")))
  with ImplicitSender with DefaultTimeout
  with Matchers with FlatSpecLike with BeforeAndAfterAll
{
  override def afterAll = shutdown()

  behavior of "PayloadConsumer"

  val consumer = system.actorOf(Props[PayloadConsumer])

  it should "ack a Payload message" in {
    within(500 millis) {
      consumer ! Payload(42, "bollocks")
      expectMsg(PayloadAck(42))
    }
  }

  it should "ignore all non-Payload messages" in {
    within(500 millis) {
      consumer ! "bollocks"
      expectNoMsg
    }
  }

  behavior of "PayloadBroadcaster"

  val broadcaster = system.actorOf(Props[PayloadBroadcaster])

  it should "process all Payload messages" in {
    val msgs = (1 to 5) map { jobId =>
      PayloadBroadcastMsg(jobId, 5, PayloadBroadcast.payloads(10))
    }
    val dones = (1 to 5) map(jobId => Done(jobId))
    msgs.par foreach (broadcaster ! _)
    within(3.seconds) {
      expectMsgAllOf(dones:_*)
    }
  }

}
