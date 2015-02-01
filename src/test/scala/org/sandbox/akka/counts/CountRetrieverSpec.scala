package org.sandbox.akka.counts

import java.io.IOException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Finders
import org.scalatest.FlatSpecLike
import org.scalatest.Matchers
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.IntegrationPatience

import com.typesafe.config.ConfigFactory

import CountRetriever.Counters
import CountRetriever.GetCounters
import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.ActorSystem
import akka.actor.actorRef2Scala
import akka.pattern.ask
import akka.testkit.DefaultTimeout
import akka.testkit.ImplicitSender
import akka.testkit.TestKit

@RunWith(classOf[org.scalatest.junit.JUnitRunner])
class CountRetrieverSpec
  extends TestKit(ActorSystem("CountRetrieverSpec", 
      ConfigFactory.parseString("akka.loglevel=DEBUG").withFallback(ConfigFactory.load)))
  with ImplicitSender with DefaultTimeout
  with Matchers with FlatSpecLike with BeforeAndAfterAll with Eventually with IntegrationPatience
{
  override def afterAll = shutdown()

  import CountRetriever._

  behavior of "Happy Path"

  implicit val ec = system.dispatcher

  private trait HappyPath {
    val simpleCountProvider = new CountProvider {}
    val retriever = countRetriever(simpleCountProvider)
  }

  it should "1.1 retrieve a single Count" in new HappyPath {
    retriever ! GetCounters(42, 1)
    within(500 millis) {
      expectMsg(Counters(42, Set(1)))
    }
  }

  it should "1.2 retrieve multiple Counts" in new HappyPath {
    retriever ! GetCounters(42, 5)
    within(500 millis) {
      expectMsg(Counters(42, Set(1, 2, 3, 4, 5)))
    }
  }

  it should "1.3 handle simultaneous GetCounters requests" in new HappyPath {
    val requests = (1 to 5) map(i => GetCounters(i, 5))
    val counters = (1 to 5) map(i => Counters(i, Set(1, 2, 3, 4, 5)))
    requests.par foreach (retriever ! _)
    val received = receiveWhile(2 seconds) {
      case msg: Counters => msg
    }
    assert(received.size == 5)
    def between(n: Int, min: Int, max: Int) = n >= min && n <= max
    assert(received forall { case Counters(jobId, counters) =>
      between(jobId, 1, 5) &&
      counters.forall(between(_, 1, 25))
    })
  }

  behavior of "Error Handling"

  it should "2.1 throw a TimeoutException in case of timeout" in {
    val slowCountProvider = new CountProvider {
      override def getNext = {
        Thread.sleep(2000)
        super.getNext
      }
    }
    val retriever = countRetriever(slowCountProvider)
    val future = retriever ? GetCounters(42, 1, 500 millis)
    var gotTimeoutException = false
    future onFailure { case _: TimeoutException => gotTimeoutException = true }
    eventually {
      assert(gotTimeoutException)
    }
  }

  it should "2.2 make DeatchWatcher work" in {
    val retriever = countRetriever(new CountProvider {})
    var terminated = false
    def onTermination(subject: ActorRef) = if (subject == retriever) terminated = true
    val deatchWatcher =
      system.actorOf(DeathWatcher.props(retriever, onTermination), "deathWatcher")
    system.stop(retriever)
    eventually {
      assert(terminated)
    }
  }

  it should "2.3 retry in case of IOException" in {
    val ioUnsafeCountProvider = new CountProvider {
      val calls = new AtomicInteger(0)
      private def throwException =
        calls.incrementAndGet % 3 == 0
      override def getNext = {
        if (throwException) throw new IOException("sorry!")
        else super.getNext
      }
    }
    val retriever = countRetriever(ioUnsafeCountProvider)
    retriever ! GetCounters(42, 7)
    within(2 seconds) {
      expectMsg(Counters(42, Set(1,2,3,4,5,6,7)))
    }
  }

  private val retrieverCount = new AtomicInteger(0)
  private val getterCount = new AtomicInteger(0)
  private def countRetriever(countProvider: CountProvider): ActorRef = {

    def countGetter(factory: ActorRefFactory): ActorRef =
      factory.actorOf(CountGetter.props(countProvider), s"countGetter-${getterCount.incrementAndGet}")

    system.actorOf(CountRetriever.props(countGetter), s"countRetriever-${retrieverCount.incrementAndGet}")
  }
}