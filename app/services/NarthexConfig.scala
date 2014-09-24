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

import java.util

import play.api.Play

import scala.collection.JavaConversions._


object NarthexConfig {
  val config = Play.current.configuration

  def configString(name: String) = config.getString(name).getOrElse(
    throw new RuntimeException(s"Missing config string: $name")
  )
  def configInt(name: String) = config.getInt(name).getOrElse(
    throw new RuntimeException(s"Missing config int: $name")
  )
  def secretList(name: String): util.List[String] = config.getStringList(name).getOrElse(List[String]("secret"))

  lazy val USER_HOME = System.getProperty("user.home")
  lazy val ORG_ID = configString("commons.orgId")
  lazy val COMMONS_HOST = configString("commons.host")
  lazy val COMMONS_TOKEN = configString("commons.token")
  lazy val COMMONS_NODE = configString("commons.node")

  lazy val API_ACCESS_KEYS = secretList("api.accessKeys")

  lazy val OAI_PMH_REPOSITORY_IDENTIFIER = configString("oai_pmh.repositoryIdentifier")
  lazy val OAI_PMH_REPOSITORY_NAME = configString("oai_pmh.repositoryName")
  lazy val OAI_PMH_ADMIN_EMAIL = configString("oai_pmh.adminEmail")
  lazy val OAI_PMH_EARLIEST_DATE_STAMP = configString("oai_pmh.earliestDateStamp")
  lazy val OAI_PMH_SAMPLE_IDENTIFIER = configString("oai_pmh.sampleIdentifier")
  lazy val OAI_PMH_PAGE_SIZE = configInt("oai_pmh.pageSize")
  lazy val OAI_PMH_ACCESS_KEYS = secretList("oai_pmh.accessKeys")

  lazy val HARVEST_TIMEOUT = config.getInt("harvest.timeout").getOrElse(3 * 60 * 1000)

  def oaiPmhKeyFits(accessKey: String) = OAI_PMH_ACCESS_KEYS.contains(accessKey)
  def apiKeyFits(accessKey: String) = API_ACCESS_KEYS.contains(accessKey)
}
