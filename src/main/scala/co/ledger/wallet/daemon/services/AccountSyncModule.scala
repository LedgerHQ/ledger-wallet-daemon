package co.ledger.wallet.daemon.services

import akka.actor.{ActorRef, ActorRefFactory, ActorSystem, Props}
import co.ledger.wallet.daemon.database.DaemonCache
import co.ledger.wallet.daemon.modules.PublisherModule.OperationsPublisherFactory
import com.google.inject.{AbstractModule, Provides}
import com.twitter.concurrent.NamedPoolThreadFactory
import com.twitter.util.{ScheduledThreadPoolTimer, Timer}
import javax.inject.{Named, Singleton}

object AccountSyncModule extends AbstractModule {
  type AccountSynchronizerWatchdogFactory = () => ActorRef
  type AccountSynchronizerFactory = (ActorRefFactory, ActorRef) => ActorRef

  @Provides def providesTimer: Timer = new ScheduledThreadPoolTimer(
    poolSize = 1,
    threadFactory = new NamedPoolThreadFactory("AccountSynchronizer-Scheduler")
  )

  @Provides
  @Singleton
  @Named("AccountSynchronizerWatchdog")
  def providesAccountSynchronizerWatchdog(system: ActorSystem, cache: DaemonCache, timer: Timer, @Named("AccountSynchronizer") synchronizer: ActorRef, publisherFactory: OperationsPublisherFactory): ActorRef = {
    system.actorOf(Props(classOf[AccountSynchronizerWatchdog], cache, timer, synchronizer, publisherFactory)
      .withDispatcher(SynchronizationDispatcher.configurationKey(SynchronizationDispatcher.Synchronizer)),
        "akka-account-synchronizer-watchdog"
      )
  }

  @Provides
  @Singleton
  @Named("AccountSynchronizer")
  def providesAccountSynchronizer(system: ActorSystem): ActorRef = {
    system.actorOf(Props(new AccountSynchronizer()).withMailbox("account-synchronizer-mailbox"), "akka-account-synchronizer")
  }

  override def configure(): Unit = ()
}
