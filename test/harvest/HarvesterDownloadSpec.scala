package harvest

import java.net.{ServerSocket, SocketTimeoutException, URL}

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HarvesterDownloadSpec extends AnyFlatSpec with Matchers {

  "openDownload" should "fail instead of hanging when the server never answers" in {
    val server = new ServerSocket(0) // accepts (backlog) but never writes a response
    try {
      val start = System.currentTimeMillis()
      a[SocketTimeoutException] should be thrownBy
        Harvester.openDownload(new URL(s"http://127.0.0.1:${server.getLocalPort}/x.xml"), 1000, 1000)
      (System.currentTimeMillis() - start) should be < 10000L
    } finally server.close()
  }
}
