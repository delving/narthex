package init.healthchecks

import com.codahale.metrics.health.HealthCheck
import com.codahale.metrics.health.HealthCheck.Result
import organization.UserRepository
import play.api.Logger

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.{Failure, Success}

class AdminUserHealthCheck(userRepository: UserRepository, val timeoutMillis: Long) extends HealthCheck {

  override def check(): Result = {
    val duration = timeoutMillis.milliseconds
    Await.ready(userRepository.hasAdmin, duration).value.get match {
      case Success(hasAdmin) => {
        if (hasAdmin) Result.healthy() else {
          Logger.warn("No admin user present. Restart Narthex to let it be inserted")
          Result.unhealthy("no admin user present")
        }
      }
      case Failure(e) => Result.unhealthy(s"Could not reach underlying store within $duration. Exception: $e")
    }
  }
}
