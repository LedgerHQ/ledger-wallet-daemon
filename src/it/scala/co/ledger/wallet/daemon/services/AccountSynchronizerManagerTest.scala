package co.ledger.wallet.daemon.services

import akka.actor.ActorRef
import com.twitter.util
import com.twitter.util.{Time, Timer, TimerTask}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps


class AccountSynchronizerManagerTest extends FlatSpec with MockitoSugar with DefaultDaemonCacheDatabaseInitializer with Matchers with ScalaFutures {

  implicit val timeout: FiniteDuration = 1 minute

  "AccountSynchronizerManager.start" should "register accounts and schedule a periodic registration" in {
    var scheduled = false

    val scheduler: Timer = new Timer {
      override protected def scheduleOnce(when: Time)(f: => Unit): TimerTask = mock[TimerTask]

      override def stop(): Unit = ()

      override protected def schedulePeriodically(when: Time, period: util.Duration)(f: => Unit): TimerTask = {
        scheduled = true
        mock[TimerTask]
      }
    }

    val manager = new AccountSynchronizerManager(defaultDaemonCache, mock[ActorRef], scheduler)

    Await.result(manager.start(), 1.minute)

    scheduled should be(true)
  }

}
