//===========================================================================
//    Copyright 2014 Delving B.V.
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

package services

import java.io.File
import java.util

import play.api.Play

import scala.collection.JavaConversions._

object NarthexConfig {
  val config = Play.current.configuration

  def configFlag(name: String): Boolean = config.getBoolean(name).getOrElse(false)
  def configString(name: String) = config.getString(name).getOrElse(
    throw new RuntimeException(s"Missing config string: $name")
  )
  def configInt(name: String) = config.getInt(name).getOrElse(
    throw new RuntimeException(s"Missing config int: $name")
  )
  def secretList(name: String): util.List[String] = config.getStringList(name).getOrElse(List("secret"))

  val USER_HOME = System.getProperty("user.home")
  val NARTHEX = new File(USER_HOME, "NarthexFiles")

  lazy val API_ACCESS_KEYS = secretList("api.accessKeys")

  lazy val HARVEST_TIMEOUT = config.getInt("harvest.timeout").getOrElse(3 * 60 * 1000)

  def apiKeyFits(accessKey: String) = API_ACCESS_KEYS.contains(accessKey)

  val ORG_ID = configString("orgId")
  val NARTHEX_DOMAIN = configString("domains.narthex")
  val NAVE_DOMAIN = configString("domains.nave")

  val SHOW_CATEGORIES = configFlag("categories")

  val TRIPLE_STORE_URL = configString("triple-store")

  val NX_NAMESPACE = "http://github.com/delving/narthex/wiki/Namespace#"
  val NX_URI_PREFIX = s"$NARTHEX_DOMAIN/resolve"
}
