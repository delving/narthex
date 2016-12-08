package organization

import nxutil.Utils

case class Profile(val firstName: String, val lastName: String, val email: String)

case class User(val userNameProposed: String, val makerOpt: Option[String],
                   val profileOpt: Option[Profile] = None){

  val actorName = Utils.sanitizedUsername(userNameProposed)
  //override val uri = s"$NX_URI_PREFIX/actor/$actorName"
  def uri(prefix: String): String = s"$prefix/actor/$actorName"
}