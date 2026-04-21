//===========================================================================
//    Copyright 2026 Delving B.V.
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

package harvest

import org.joda.time.{DateTime, DateTimeZone}
import org.scalatest.flatspec._
import org.scalatest.matchers._

class HarvestingFromFormatSpec extends AnyFlatSpec with should.Matchers {

  private val sampleTime = new DateTime(2026, 3, 9, 14, 30, 45, DateTimeZone.UTC)

  "formatFromParameter" should "produce date-only format when justDate is true" in {
    val result = Harvesting.formatFromParameter(sampleTime, justDate = true)
    result shouldBe "2026-03-09"
  }

  it should "produce full ISO UTC format when justDate is false" in {
    val result = Harvesting.formatFromParameter(sampleTime, justDate = false)
    result shouldBe "2026-03-09T14:30:45Z"
  }

  it should "strip sub-second precision when justDate is false" in {
    val withMillis = sampleTime.withMillis(sampleTime.getMillis + 123)
    val result = Harvesting.formatFromParameter(withMillis, justDate = false)
    result shouldBe "2026-03-09T14:30:45Z"
  }
}
