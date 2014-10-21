package services

import org.scalatest.{FlatSpec, Matchers}

class TestBaseXTools extends FlatSpec with Matchers with BaseXTools {

  "date parsing" should "be tolerant" in {
    val xsdTime = fromXSDDateTime("2014-10-06T07:44:08.854+02:00")
    xsdTime.getMillis should be(1412574248854L)
    toXSDString(xsdTime) should be("2014-10-06T07:44:08.854+02:00")
    val utcDateTime = fromUTCDateTime("1957-03-20T20:30:00Z")
    utcDateTime.getMillis should be(-403414200000L)
    toXSDString(utcDateTime) should be("1957-03-20T21:30:00.000+01:00")
    val utcDate = fromUTCDateTime("1957-03-20")
    utcDate.getMillis should be(-403491600000L)
    toXSDString(utcDate) should be("1957-03-20T00:00:00.000+01:00")
    toBasicString(utcDate) should be("1957-03-20T00:00:00Z")
  }
}
