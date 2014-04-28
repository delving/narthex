package services

import org.scalatest._
import scala.util.Random

class XRaySpec extends FlatSpec with XRay {

  val random = new RandomSample("test", 10, new Random(939))

  "Random sampler" should "keep the right values" in {

    val strings = for (i <- 1 to 50) yield s"string$i"
    strings.foreach(random.record)

    val values = random.values
    assert(values.size == 10)

    println(random.values)
  }
}
