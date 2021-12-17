package mapping

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

object ThreadPool {

  val impl = Executors.newFixedThreadPool(12);

  def execute(r: Runnable): Unit = {
    impl.execute(r)
  }

  def shutdown(): Unit = {
    impl.shutdown()
  }
}
