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
      ConfigFactory.parseString("akka.loglevel=DEBUG").withFallback(ConfigFactory.load)))
  with ImplicitSender with DefaultTimeout
  with Matchers with FlatSpecLike with BeforeAndAfterAll
{
  override def afterAll = shutdown()

  import CountRetriever._

  behavior of "Happy Path"

  private trait HappyPath {
    val simpleCountProvider = new CountProvider {}
    val retriever = countRetriever(simpleCountProvider)
  }

  it should "1. retrieve a single Count" in new HappyPath {
    retriever ! GetCounters(42, 1)
    within(500 millis) {
      expectMsg(Counters(42, Set(1)))
    }
  }

  it should "2. retrieve multiple Counts" in new HappyPath {
    retriever ! GetCounters(42, 5)
    within(500 millis) {
      expectMsg(Counters(42, Set(1, 2, 3, 4, 5)))
    }
  }

  it should "3. handle simultaneous GetCounters requests" in new HappyPath {
    val requests = (1 to 5) map(i => GetCounters(i, 5))
    val counters = (1 to 5) map(i => Counters(i, Set(1, 2, 3, 4, 5)))
    requests.par foreach (retriever ! _)
    val received = receiveWhile(2 seconds) {
      case msg: Counters => msg
    }
    println(received)
    assert(received.size == 5)
    def between(n: Int, min: Int, max: Int) = n >= min && n <= max
    assert(received forall { case Counters(jobId, counters) =>
      between(jobId, 1, 5) &&
      counters.forall(between(_, 1, 25))
    })
  }

  private val retrieverCount = new AtomicInteger(0)
  private val getterCount = new AtomicInteger(0)
  private def countRetriever(countProvider: CountProvider): ActorRef = {

    def countGetter(factory: ActorRefFactory): ActorRef =
      factory.actorOf(CountGetter.props(countProvider), s"countGetter-${getterCount.incrementAndGet}")

    system.actorOf(CountRetriever.props(countGetter), s"countRetriever-${retrieverCount.incrementAndGet}")
  }
}