package init

import com.google.inject.AbstractModule
import com.kenshoo.play.metrics.Metrics
import com.kenshoo.play.metrics.MetricsImpl

import services.MailService
import services.PlayMailService
import triplestore.TripleStore
import triplestore.Fuseki

class NarthexBindings extends AbstractModule {

  override def configure(): Unit = {
    bind(classOf[NarthexLifecycle]).asEagerSingleton()
    bind(classOf[NarthexDatadog]).asEagerSingleton()
    // Eager: its constructor schedules the daily background discovery sweep;
    // as a lazy singleton the timer would only start on first API touch.
    bind(classOf[discovery.DatasetDiscoveryService]).asEagerSingleton()
    val _ = bind(classOf[MailService]).to(classOf[PlayMailService])
    val _ = bind(classOf[TripleStore]).to(classOf[Fuseki])
    val _ = bind(classOf[Metrics]).to(classOf[MetricsImpl])
  }

}
