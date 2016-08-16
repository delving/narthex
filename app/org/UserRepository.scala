package org

import scala.concurrent.Future

object UserRepository {

  object Mode extends Enumeration {

    val PROPERTY_NAME = "backingUserRepo"

    type Mode = Value
    val MOCK, TS = Value

    def fromConfigString(valueOpt: Option[String]): Mode = {
      valueOpt match {
        case None => throw new RuntimeException(s"$PROPERTY_NAME not specified")
        case Some(v) => v match {
          case "mock" => Mode.MOCK
          case "ts" => Mode.TS
          case _ => throw new RuntimeException(s"Unknown $PROPERTY_NAME specified")
        }
      }
    }
  }
}

trait UserRepository {

  def insertAdmin(passwd: String): Future[User]

  def hasAdmin: Future[Boolean]

  /**
    * Retrieve a an actor known to exist
    *
    * @throws IllegalArgumentException if the actor does not exist
    */
  def loadActor(name: String): Future[User]

  def emailFromUri(actorUri: String): Future[Option[String]]

  def adminEmails: Future[List[String]]

  def listSubActors(user: User): List[Map[String, String]]

  def createSubActor(adminActor: User, usernameString: String, password: String): Future[Option[User]]

  def makeAdmin(userName: String): Future[Option[User]]

  def removeAdmin(userName: String): Future[Option[User]]

  def deleteActor(userName: String): Future[Option[User]]

  def disableActor(userName: String): Future[Option[User]]

  def enableActor(userName: String): Future[Option[User]]

  def setProfile(actor: User, profile: Profile): Future[Unit]

  def setPassword(actor: User, newPassword: String): Future[Boolean]
}