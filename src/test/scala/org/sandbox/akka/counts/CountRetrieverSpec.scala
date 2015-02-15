package org.sandbox.akka.counts

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.implicitNotFound
import scala.concurrent.duration.DurationInt
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Finders
import org.scalatest.FlatSpecLike
import org.scalatest.Matchers
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.IntegrationPatience
import com.typesafe.config.ConfigFactory
import CountGetter.Counter
import CountRetriever.Counters
import CountRetriever.GetCounters
import CountRetriever.TimeoutExpired
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.ActorSystem
import akka.actor.OneForOneStrategy
import akka.actor.Props
import akka.actor.SupervisorStrategy
import akka.actor.actorRef2Scala
import akka.pattern.ask
import akka.persistence.PersistentView
import akka.testkit.DefaultTimeout
import akka.testkit.ImplicitSender
import akka.testkit.TestActorRef
import akka.testkit.TestKit
import scala.concurrent.Await

@RunWith(classOf[org.scalatest.junit.JUnitRunner])
class CountRetrieverSpec
  extends TestKit(ActorSystem("CountRetrieverSpec", 
      ConfigFactory.parseString("akka.loglevel=WARNING").withFallback(ConfigFactory.load)))
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

  it should "2.1 send a TimeoutExpired message in case of timeout" in {
    val slowCountProvider = new CountProvider {
      override def getNext = {
        Thread.sleep(2000)
        super.getNext
      }
    }
    val retriever = countRetriever(slowCountProvider)
    val myTimeout = 500.millis
    retriever ! GetCounters(42, 1, myTimeout)
    within(1 second) {
      expectMsg(TimeoutExpired(42, myTimeout))
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

  behavior of "Persistence"

  class TestView(val persistenceId: String, val viewId: String,
      condition: Set[Counter] => Boolean, onTrue: => Unit)
    extends PersistentView
  {
    var persistentMessages: Set[Counter] = Set()

    override def receive: Receive = {
      case msg: Counter if isPersistent =>
        persistentMessages += msg
        if (condition(persistentMessages)) onTrue
    }
  }

  private trait Persistence { persistence =>
    val simpleCountProvider = new CountProvider {}
    val persistenceId = "bollocks"
    val retriever = countRetriever(simpleCountProvider, Some(persistenceId))
  }

  it should "3.1 persist all Counter messages" in new Persistence {
    retriever ! GetCounters(666, 7)
    val expectedMsgs = ((1 to 7) map (Counter(666, _))).toSet
    var gotAllCounters = false
    val persistentViewActor =
      system.actorOf(Props(new TestView(persistenceId, s"$persistenceId-view",
          _ == expectedMsgs, gotAllCounters = true)))
    eventually {
      expectMsg(Counters(666, Set(1,2,3,4,5,6,7)))
      assert(gotAllCounters)
    }
  }

  it should "3.2 recover in case of restart" in {
    val unsafeCountProvider = new CountProvider {
      val calls = new AtomicInteger(0)
      private def throwException =
        calls.incrementAndGet % 3 == 0
      override def getNext = {
        if (throwException) throw new Exception("ohMyGod!")
        else super.getNext
      }
    }
    class Restarter extends Actor {
      override def receive: Receive = {
        case (props: Props, name: String) => sender ! context.actorOf(props, name)
      }
      override val supervisorStrategy = OneForOneStrategy() {
        case _: Exception => SupervisorStrategy.Restart
      }
    }
    val restarter = system.actorOf(Props(new Restarter), "restarter")
    val propsAndName = countRetrieverProps(unsafeCountProvider, Some("recover-me"))
    val retriever =
      Await.result(restarter ? propsAndName, 1 second).asInstanceOf[ActorRef]
    retriever ! GetCounters(77, 7)
    within(2 seconds) {
      expectMsg(Counters(77, Set(1,2,3,4,5,6,7)))
    }
  }

  private val retrieverCount = new AtomicInteger(0)
  private val getterCount = new AtomicInteger(0)
  private def countRetrieverProps(countProvider: CountProvider, persistenceId: Option[String] = None): (Props,String) = {
    def countGetter(factory: ActorRefFactory): ActorRef =
      factory.actorOf(CountGetter.props(countProvider), s"countGetter-${getterCount.incrementAndGet}")

    val name = s"countRetriever-${retrieverCount.incrementAndGet}"
    (CountRetriever.props(countGetter, persistenceId getOrElse s"$name-persistence"), name)
  }
  private def countRetriever(countProvider: CountProvider, persistenceId: Option[String] = None): ActorRef = {
    val (props, name) = countRetrieverProps(countProvider, persistenceId)
    system.actorOf(props, name)
  }
}