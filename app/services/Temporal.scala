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

import org.joda.time.format.ISODateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}

import scala.xml.NodeSeq

object Temporal {

  def timeToString(dateTime: DateTime) = ISODateTimeFormat.dateTimeNoMillis().print(dateTime)

  def timeToUTCString(dateTime: DateTime) = timeToString(dateTime.withZone(DateTimeZone.UTC))

  def timeToLocalString(dateTime: DateTime) = ISODateTimeFormat.dateTimeNoMillis().print(dateTime.toLocalDateTime)

  def stringToTime(dateString: String) = ISODateTimeFormat.dateOptionalTimeParser().parseDateTime(dateString)

  def nodeSeqToTime(nodeSeq: NodeSeq): Option[DateTime] = if (nodeSeq.nonEmpty) Some(stringToTime(nodeSeq.text)) else None

  def nowFileName(name: String, extension: String) = s"${name}_${timeToLocalString(new DateTime()).replaceAll("[^\\d]","_")}$extension"
}
