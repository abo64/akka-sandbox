package org.sandbox.akka.broadcast

import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
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
}
