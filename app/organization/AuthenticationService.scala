package organization

import nxutil.Utils
import triplestore.Sparql._
import triplestore.TripleStore

import scala.concurrent.{ExecutionContext, Future}

trait AuthenticationService {

  def authenticate(actorName: String, password: String): Future[Boolean]

}

class MockAuthenticationService extends AuthenticationService {
  override def authenticate(actorName: String, password: String) = Future.successful(true)
}

class TsBasedAuthenticationService()(implicit ec: ExecutionContext, ts: TripleStore) extends AuthenticationService {
  def authenticate(actorName: String, password: String): Future[Boolean] = {
    val passwordHashString = Utils.hashPasswordUnsecure(password, actorName)
    ts.query(getActorWithPassword(actorName, passwordHashString)).map { v => !v.isEmpty }
  }
}