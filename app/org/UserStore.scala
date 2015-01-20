//===========================================================================
//    Copyright 2015 Delving B.V.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//===========================================================================

package org

import java.security.MessageDigest

import com.hp.hpl.jena.rdf.model._
import services.NarthexConfig._
import triplestore.TripleStoreClient
import triplestore.TripleStoreClient._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object UserStore {

  val graphName = s"$NX_NAMESPACE/users"

  case class NXUserDetails(firstName: String, lastName: String, email: String)

  case class NXUser(usernameProposed: String, detailsOpt: Option[NXUserDetails] = None, administrator: Boolean = false) {
    val username = usernameProposed.replaceAll("[^\\w]", "_").toLowerCase
    val uri = s"$NX_URI_PREFIX/user/$username"
  }

  case class USProp(name: String, dataType: PropType = stringProp) {
    val uri = s"${NX_NAMESPACE}User-Attributes#$name"
  }

  val ADMINISTRATOR_ROLE = "administrator"

  val userEMail = USProp("userEMail")
  val userFirstName = USProp("userFirstName")
  val userLastName = USProp("userLastName")
  val userRole = USProp("userRole")
  val userPasswordHash = USProp("userPasswordHash")

}

class UserStore(client: TripleStoreClient) {

  import org.UserStore._

  val digest = MessageDigest.getInstance("SHA-256")
  val futureModel = client.dataGet(graphName).fallbackTo(Future(ModelFactory.createDefaultModel()))

  private def hash(string: String) = {
    digest.reset()
    val ba = digest.digest(string.getBytes("UTF-8"))
    ba.map("%02x".format(_)).mkString
  }

  def authenticate(username: String, password: String): Future[Option[NXUser]] = futureModel.flatMap { m =>
    val hashLiteral: Literal = m.createLiteral(hash(username + password))
    if (m.isEmpty) {
      val godUser = NXUser(username, administrator = true)
      val userResource: Resource = m.getResource(godUser.uri)
      m.add(userResource, m.getProperty(userPasswordHash.uri), hashLiteral)
      m.add(userResource, m.getProperty(userRole.uri), m.createLiteral(ADMINISTRATOR_ROLE))
      client.dataPost(graphName, m).map(ok => Some(godUser))
    }
    else {
      val user: NXUser = NXUser(username)
      val userResource: Resource = m.getResource(user.uri)
      val exists = m.listStatements(userResource, m.getProperty(userPasswordHash.uri), hashLiteral).hasNext
      val userOpt = if (exists) {
        val admin = m.listStatements(userResource, m.getProperty(userRole.uri), m.createLiteral(ADMINISTRATOR_ROLE)).hasNext
        Some(user.copy(administrator = admin))
      }
      else {
        None
      }
      Future(userOpt)
    }
  }


}
