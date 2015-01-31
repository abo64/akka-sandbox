package org.sandbox.akka.counts

import java.util.concurrent.atomic.AtomicInteger

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Finders
import org.scalatest.FlatSpecLike
import org.scalatest.Matchers

import scala.concurrent.duration.DurationInt

import com.typesafe.config.ConfigFactory

import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.ActorSystem
import akka.testkit.DefaultTimeout
import akka.testkit.ImplicitSender
import akka.testkit.TestKit

@RunWith(classOf[org.scalatest.junit.JUnitRunner])
class CountRetrieverSpec
  extends TestKit(ActorSystem("CountRetrieverSpec", 
      ConfigFactory.parseString("akka.loglevel=WARNING")))
  with ImplicitSender with DefaultTimeout
  with Matchers with FlatSpecLike with BeforeAndAfterAll
{
  override def afterAll = shutdown()

  import CountRetriever._

  behavior of "CountRetriever"

  it should "1. retrieve a single Count" in {
    val simpleCountProvider = new CountProvider {}
    val retriever = countRetriever(simpleCountProvider, "countRetriever-1")
    retriever ! GetCounters(1)
    within(500 millis) {
      expectMsg(Counters(Set(1)))
    }
  }

  private def countRetriever(countProvider: CountProvider, countRetrieverName: String): ActorRef = {
    val getterCount = new AtomicInteger(0)

    def countGetter(factory: ActorRefFactory): ActorRef =
      factory.actorOf(CountGetter.props(countProvider), s"countGetter-${getterCount.incrementAndGet}")

    system.actorOf(CountRetriever.props(countGetter), countRetrieverName)
  }
}