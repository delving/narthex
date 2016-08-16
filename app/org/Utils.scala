package org

import java.security.MessageDigest

object Utils {

  def sanitizedUsername(proposed: String) = proposed.replaceAll("[^\\w-]", "").toLowerCase

  // that's not how you do hashing/salting, folks...
  def hashPasswordUnsecure(password: String, salt: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val salted = password + salt
    val ba = digest.digest(salted.getBytes("UTF-8"))
    ba.map("%02x".format(_)).mkString
  }
}
