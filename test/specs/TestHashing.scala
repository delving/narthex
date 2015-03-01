package specs

import org.scalatest.{FlatSpec, Matchers}
import services.MissingLibs
import services.MissingLibs.HashType

class TestHashing extends FlatSpec with Matchers {

  "base32 hashing" should "be good for urls" in {

    val hashType = HashType.SHA256

    val samples = List(
      "one",
      "two",
      "3.1415926535etc",
      "and what about a very long value where nobody really knows when it will end, but we can just guess"
    )

    val hashed = samples.map(MissingLibs.hashBase32(_, hashType))

    hashed.map(h => println(s""" "$h", """))

    val expected = List(
      "O2JMHLJVIC5YAPACBM5O4ZWNRCDREMRU5IGG44KDYCW5OP7UGHWQ",
      "H7CMZ7TULBYOFQGZT5Y7GD7QMVWI33OUDTA5PU6TO2YNXZUF4LZQ",
      "JVOZCQPQVUMV6PXC7Q7GM5G4ELITHN7J5JYWGBMTG6SCIMAPMD4Q"
    )

    hashed.zip(expected).map { pair =>
      pair._1 should be(pair._2)
    }


  }
}
