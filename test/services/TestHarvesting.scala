package services

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.SpanSugar
import org.scalatest.{FlatSpec, Matchers}

class TestHarvesting extends FlatSpec with Matchers with Harvesting with ScalaFutures with SpanSugar {

  "The AdLib Harvester" should "fetch pages" in {

    val testUrl = "http://umu.adlibhosting.com/api/wwwopac.ashx"
    val testDatabase = "collect"

    whenReady(fetchAdLibPage(testUrl, testDatabase), timeout(10 seconds)) {
      p =>
        p.diagnostic.itemCount should be(2)
        if (p.diagnostic.isLast) {
          println("done??")
        }
        else {
          whenReady(fetchAdLibPage(testUrl, testDatabase, Some(p.diagnostic)), timeout(10 seconds)) {
            pp =>
              println(s"second: ${pp.records}")
              pp.diagnostic.itemCount should be(2)
          }
        }
    }
  }

  "The PMH Harvester" should "fetch pages" in {

    val testUrl = "http://62.221.199.184:7829/oai"
    val testSet = ""
    val testPrefix = "oai_dc"

    var count = 0

    whenReady(fetchPMHPage(testUrl, testSet, testPrefix, None), timeout(30 seconds)) {
      p =>
        println(s"page $count: ${p.records.length}")
        println(s"Resumption: ${p.resumptionToken}")

        def next(token: PMHResumptionToken): Unit = {
          whenReady(fetchPMHPage(testUrl, testSet, testPrefix, Some(token)), timeout(30 seconds)) {
            pp =>
              count += 1
              println(s"page $count: ${pp.records.length}")
              println(s"Resumption: ${pp.resumptionToken}")
              pp.resumptionToken match {
                case Some(t) =>
                  next(t)
                case None =>
                  println("FINISHED")
              }
          }
        }

        p.resumptionToken match {
          case Some(token) =>
            next(token)
          case None =>
            println("FINISHED RIGHT AWAY")
        }
    }
  }
}
