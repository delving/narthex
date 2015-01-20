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

  case class NXUser(userNameProposed: String, detailsOpt: Option[NXUserDetails] = None, administrator: Boolean = false) {
    val userName = userNameProposed.replaceAll("[^\\w]", "_").toLowerCase
    val uri = s"$NX_URI_PREFIX/user/$userName"
  }

  case class USProp(name: String, dataType: PropType = stringProp) {
    val uri = s"$NX_NAMESPACE/User-Attributes#$name"
  }

  val ADMINISTRATOR_ROLE = "administrator"

  val userName = USProp("userName")
  val userEMail = USProp("userEMail")
  val userFirstName = USProp("userFirstName")
  val userLastName = USProp("userLastName")
  val userRole = USProp("userRole")
  val userPasswordHash = USProp("userPasswordHash")

  def userIntoModel(user: NXUser, passwordHash: Literal, m: Model): Option[NXUser] = {
    val userResource: Resource = m.getResource(user.uri)
    val exists = m.listStatements(userResource, m.getProperty(userPasswordHash.uri), null).hasNext
    if (exists) None
    else {
      // userPasswordHash
      m.add(userResource, m.getProperty(userPasswordHash.uri), passwordHash)
      // userName
      m.add(userResource, m.getProperty(userName.uri), user.userName)
      // optional admin
      if (user.administrator) m.add(userResource, m.getProperty(userRole.uri), m.createLiteral(ADMINISTRATOR_ROLE))
      Some(user)
    }
  }
}

class UserStore(client: TripleStoreClient) {

  import org.UserStore._

  val digest = MessageDigest.getInstance("SHA-256")
  val futureModel = client.dataGet(graphName).fallbackTo(Future(ModelFactory.createDefaultModel()))

  private def hashLiteral(string: String, m: Model): Literal = {
    digest.reset()
    val ba = digest.digest(string.getBytes("UTF-8"))
    val hash = ba.map("%02x".format(_)).mkString
    m.createLiteral(hash)
  }

  def authenticate(userNameString: String, password: String): Future[Option[NXUser]] = futureModel.flatMap { m =>
    val hash = hashLiteral(userNameString + password, m)
    if (m.isEmpty) {
      val godUser = NXUser(userNameString, administrator = true)
      userIntoModel(godUser, hash, m)
      client.dataPost(graphName, m).map(ok => Some(godUser))
    }
    else {
      val user: NXUser = NXUser(userNameString)
      val userResource: Resource = m.getResource(user.uri)
      val exists = m.listStatements(userResource, m.getProperty(userPasswordHash.uri), hash).hasNext
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

  def introduce(userNameString: String, password: String): Future[Option[NXUser]] = futureModel.flatMap { m =>
    val hash = hashLiteral(userNameString + password, m)
    userIntoModel(NXUser(userNameString), hash, m).map { user: NXUser =>
      val sparql =
        s"""
         |INSERT DATA { GRAPH <$graphName> {
         | <${user.uri}> <${m.getProperty(userName.uri)}> "${user.userName}" .
         | <${user.uri}> <${m.getProperty(userPasswordHash.uri)}> "${hash.getString}" .
         |} }
       """.stripMargin
      client.update(sparql).map(ok => Some(user))
    } getOrElse {
      Future(None)
    }
  }

  def setActive(userNameString: String, active: Boolean): Unit = {
    // todo: implement
  }

}
