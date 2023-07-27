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

  object DelayUnit {
    val MINUTES = DelayUnit("minutes", 1000 * 60)
    val HOURS = DelayUnit("hours", MINUTES.millis * 60)
    val DAYS = DelayUnit("days", HOURS.millis * 24)
    val WEEKS = DelayUnit("weeks", DAYS.millis * 7)

    val ALL_UNITS = List(WEEKS, DAYS, HOURS, MINUTES)

    def fromString(string: String): Option[DelayUnit] = ALL_UNITS.find(s => s.matches(string))
  }

  case class DelayUnit(name: String, millis: Long) {
    override def toString = name

    def matches(otherName: String) = name == otherName

    def after(previous: DateTime, delay: Int) = {
      val nonzeroDelay = if (delay <= 0) 1 else delay
      new DateTime(previous.getMillis + millis * nonzeroDelay)
    }
  }

  val ExtractDate = ".*(\\d{4})_(\\d{2})_(\\d{2})_(\\d{2})_(\\d{2}).*".r

  def fileNameToLocalString(fileName: String) = {
    try {
      val ExtractDate(year, month, day, hour, minute) = fileName
      val dateTime = new DateTime(year.toInt, month.toInt, day.toInt, hour.toInt, minute.toInt)
      timeToLocalString(dateTime)
    } catch {
      case m: MatchError => "UNKNOWN"
    }
  }

}
