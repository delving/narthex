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
    bind(classOf[MailService]).to(classOf[PlayMailService])
    bind(classOf[TripleStore]).to(classOf[Fuseki])
    bind(classOf[Metrics]).to(classOf[MetricsImpl])
  }

}
