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

import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormat, ISODateTimeFormat}

import scala.xml.NodeSeq

object Temporal {


  val XSD_FORMATTER = ISODateTimeFormat.dateTime()
  val UTC_FORMATTER = ISODateTimeFormat.dateOptionalTimeParser()
  val BASIC = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

  def toXSDDateTime(dateTime: DateTime) = XSD_FORMATTER.print(dateTime)

  def fromXSDDateTime(dateString: String) = XSD_FORMATTER.parseDateTime(dateString)

  def fromXSDDateTime(nodeSeq: NodeSeq): Option[DateTime] = if (nodeSeq.nonEmpty) Some(fromXSDDateTime(nodeSeq.text)) else None

  def toBasicString(dateTime: DateTime) = BASIC.print(dateTime)

  def fromUTCDateTime(dateString: String) = UTC_FORMATTER.parseDateTime(dateString)

}
