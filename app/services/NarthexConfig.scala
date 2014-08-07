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

import play.api.Play

object NarthexConfig {
  def string(name: String) = Play.current.configuration.getString(name).getOrElse(throw new RuntimeException(s"Missing config: $name"))

  lazy val ORG_ID = string("commons.orgId")
  lazy val COMMONS_HOST = string("commons.host")
  lazy val COMMONS_TOKEN = string("commons.token")
  lazy val COMMONS_NODE = string("commons.node")

  lazy val OAI_PMH_REPOSITORY_IDENTIFIER = string("oai_pmh.repositoryIdentifier")
  lazy val OAI_PMH_REPOSITORY_NAME = string("oai_pmh.repositoryName")
  lazy val OAI_PMH_ADMIN_EMAIL = string("oai_pmh.adminEmail")
  lazy val OAI_PMH_EARLIEST_DATE_STAMP = string("oai_pmh.earliestDateStamp")
  lazy val OAI_PMH_SAMPLE_IDENTIFIER = string("oai_pmh.sampleIdentifier")
}
