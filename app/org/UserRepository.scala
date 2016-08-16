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

  def insertAdmin(passwd: String): User

  def hasAdmin: Boolean

  /**
    * Retrieve a an actor known to exist
    *
    * @param name
    * @return
    * @throws IllegalArgumentException if the actor does not exist
    */
  def loadActor(name: String): Future[User]

  def emailFromUri(actorUri: String): Option[String]

  def adminEmails: List[String]

  /**
    * This interface is kind of odd...
    */
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

/**
  * In-memory impl for use during development. This impl suffers from race-conditions but it is sufficient during dev
  */
class MockUserRepository(val uriPrefix: String) extends UserRepository {
  import scala.concurrent.ExecutionContext.Implicits.global

  val topActorUsername = "admin"

  var users = Set[User]()
  var admins = Set[String]()
  var disabled = Set[String]()

  var userToPassword = Map[String, String]()

  override def insertAdmin(passwd: String) = {
    val user = new User("admin", None, Some(Profile("John", "Doe", "admin@devorg.some")))
    val pw = Utils.hashPasswordUnsecure(passwd, user.actorName)
    users = users + user
    user
  }

  override def hasAdmin = users.filter(u => u.actorName.equals(topActorUsername)).nonEmpty

  /**
    * Retrieve a an actor known to exist
    *
    * @param name
    * @return
    * @throws IllegalArgumentException if the actor does not exist
    */
  override def loadActor(name: String) = Future.successful(
    users.find(u => u.actorName.equals(name)).getOrElse(throw new IllegalArgumentException("barf"))
  )

  override def emailFromUri(actorUri: String) = users.
    find{ p => p.uri(uriPrefix).equals(actorUri)}.
    map{ u => u.profileOpt.getOrElse(throw new IllegalArgumentException("no")).email
  }

  override def adminEmails = users.
    filter( u => admins.contains(u.actorName)).
    map{ u => u.profileOpt.getOrElse(throw new IllegalArgumentException("no")).email}.toList

  override def listSubActors(user: User) = users.
    filter(p => p.makerOpt.isDefined).
    filter(p => p.makerOpt.get.equals(user.actorName)).
    map { u: User =>
      val adminStr = if (admins.contains(u.actorName)) "true" else "false"
      val enabledStr = if (disabled.contains(u.actorName)) "true" else "false"
      Map(
        "userName" -> u.actorName,
        "isAdmin" -> adminStr,
      "userEnabled" -> enabledStr
      )
    }.toList

  override def createSubActor(adminActor: User, username: String, password: String) = {
    val passwd = Utils.hashPasswordUnsecure(username, password)
    val u = User(username, Some(adminActor.actorName), None)
    users = users + u
    userToPassword = userToPassword + (u.actorName -> passwd)
    Future.successful(Some(u))
  }

  override def makeAdmin(userName: String) = loadActor(userName).map { u =>
    admins = admins + u.actorName
    Some(u)
  }

  override def removeAdmin(userName: String) = loadActor(userName).map { u =>
    admins = admins - u.actorName
    Some(u)
  }

  override def deleteActor(userName: String) = {
    loadActor(userName).map { u =>
      users = users - u
      Some(u)
    }
  }

  override def disableActor(userName: String) = {
    val eventualUser: Future[User] = loadActor(userName)
    eventualUser.map{ u =>
      disabled = disabled + userName
      Some(u)
    }
  }

  override def enableActor(userName: String) = loadActor(userName).map( u => Some(u))

  override def setProfile(actor: User, profile: Profile) = {
    val user = actor.copy(profileOpt = Some(profile))
    Future.successful((): Unit)
  }

  override def setPassword(actor: User, newPassword: String) = {
    val unsecure: String = Utils.hashPasswordUnsecure(actor.actorName, newPassword)
    userToPassword = (userToPassword - actor.actorName) + (actor.actorName -> unsecure)
    Future.successful(true)
  }
}