package org

import java.security.MessageDigest

object Utils {

  // to be replaced by stormpath, fixing the bad usage of salt now is wasted effort
  def hashPasswordUnsecure(password: String, salt: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val salted = password + salt
    val ba = digest.digest(salted.getBytes("UTF-8"))
    ba.map("%02x".format(_)).mkString
  }
}
