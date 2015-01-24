package org.sandbox.akka.broadcast

import scala.concurrent.duration.DurationInt
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Finders
import org.scalatest.FlatSpecLike
import org.scalatest.Matchers
import akka.actor.ActorSystem
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import akka.actor.Props
import BroadcastApp._
import org.junit.runner.RunWith
import akka.testkit.DefaultTimeout
import com.typesafe.config.ConfigFactory

@RunWith(classOf[org.scalatest.junit.JUnitRunner])
class BroadcastSpec
  extends TestKit(ActorSystem("BroadcastSpec", ConfigFactory.parseString("akka.loglevel=WARNING")))
  with ImplicitSender
  with DefaultTimeout with Matchers with FlatSpecLike with BeforeAndAfterAll
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
