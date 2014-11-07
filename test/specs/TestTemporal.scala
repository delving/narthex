package specs

import org.joda.time.{DateTime, LocalDateTime}
import org.scalatest.{FlatSpec, Matchers}
import services.Temporal._

class TestTemporal extends FlatSpec with Matchers{

  "date parsing" should "be tolerant" in {

    println(s"DateTime: ${new DateTime()}")
    println(s"LocalDateTime: ${new LocalDateTime()}")

    def xform(string: String) = {
      val t = stringToTime(string)
      val s = timeToString(t)
      println(s"$string =XSD=> $s")
      s
    }

    xform("2014-10-06T07:44:08.854+02:00") should be ("2014-10-06T07:44:08+02:00")
    xform("2014-10-06T07:44:08.854+01:00") should be ("2014-10-06T08:44:08+02:00")
    xform("2014-11-06T07:44:08.854+01:00") should be ("2014-11-06T07:44:08+01:00")
    xform("2014-11-06T07:44:08+01:00") should be ("2014-11-06T07:44:08+01:00")
    xform("2014-11-06T07:44:08Z") should be ("2014-11-06T08:44:08+01:00")
    xform("2014-11-06") should be ("2014-11-06T00:00:00+01:00")

    def utc(string: String) = {
      val t = stringToTime(string)
      val s = timeToUTCString(t)
      println(s"$string =UTC=> $s")
      s
    }

    utc("2014-11-06T07:44:08.854+01:00") should be ("2014-11-06T06:44:08Z")
    utc("2014-11-06T07:44:08+01:00") should be ("2014-11-06T06:44:08Z")
    utc("2014-11-06T07:44:08Z") should be ("2014-11-06T07:44:08Z")
    utc("2014-11-06") should be ("2014-11-05T23:00:00Z")

    println(nowFileName("evil", ".hex"))
  }
}
