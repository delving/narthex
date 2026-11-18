package harvest

import org.joda.time.{DateTime, LocalDate}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import init.NarthexConfig

class NightlyRefreshSpec extends AnyFlatSpec with Matchers {

  private val window = (60, 360) // 01:00-06:00
  private val day = new LocalDate(2026, 11, 18)

  "parseWindow" should "read HH:mm-HH:mm and reject a window across midnight" in {
    NarthexConfig.parseWindow("01:00-06:00") shouldBe Some((60, 360))
    NarthexConfig.parseWindow("23:00-02:00") shouldBe None
    NarthexConfig.parseWindow("nonsense") shouldBe None
  }

  "refreshSlot" should "be fixed per spec, inside the window, and spread across specs" in {
    val slots = (1 to 200).map(i => PeriodicHarvest.refreshSlot(s"enb-$i-objecten", day, window))
    PeriodicHarvest.refreshSlot("enb-1-objecten", day, window) shouldBe slots.head
    all(slots.map(_.getMinuteOfDay)) should (be >= 60 and be < 360)
    slots.map(_.getHourOfDay).distinct.size shouldBe 5
  }

  "nightlyRefreshDue" should "fire from the slot until the window ends, once per day" in {
    val spec = "enb-83-archeologischeobjecten"
    val slot = PeriodicHarvest.refreshSlot(spec, day, window)
    def due(now: DateTime, lastFull: Option[DateTime], lastError: Option[DateTime] = None, days: Int = 1) =
      PeriodicHarvest.nightlyRefreshDue(now, spec, window, days, lastFull, lastError)

    due(slot.minusMinutes(1), Some(slot.minusDays(1))) shouldBe false
    due(slot, Some(slot.minusDays(1))) shouldBe true
    due(slot, None) shouldBe true
    due(slot.plusMinutes(5), Some(slot.plusMinutes(2))) shouldBe false // done tonight
    due(day.toDateTimeAtStartOfDay.plusHours(7), Some(slot.minusDays(1))) shouldBe false // window over
    due(slot, Some(slot.minusDays(1)), lastError = Some(slot.plusSeconds(1))) shouldBe false
    due(slot, Some(slot.minusDays(1)), days = 0) shouldBe false
    due(slot, Some(slot.minusDays(3)), days = 7) shouldBe false
    due(slot, Some(slot.minusDays(7)), days = 7) shouldBe true
  }
}
