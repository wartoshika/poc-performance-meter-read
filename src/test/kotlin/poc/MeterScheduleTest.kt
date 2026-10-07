package poc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MeterScheduleTest {
    @Test
    fun `360000 meters spread evenly to 400 reads per second`() {
        val schedule = MeterSchedule(360_000, 900)
        val perSecond = IntArray(900)
        var previous = -1.0
        for (p in 0 until schedule.size) {
            val due = schedule.dueMillis(0, p, 0)
            assertTrue(due >= previous, "due times must be ascending")
            previous = due
            perSecond[(due / 1000).toInt()]++
        }
        assertEquals(360_000, perSecond.sum())
        assertTrue(perSecond.all { it in 398..402 }, "min ${perSecond.min()} max ${perSecond.max()}")
    }

    @Test
    fun `phase is the fractional part of n times the golden ratio`() {
        assertEquals(0.6180339887, MeterSchedule.phase(1), 1e-12)
        assertEquals(0.2360679774, MeterSchedule.phase(2), 1e-9)
        assertEquals("MTR000000042", MeterSchedule.meterId(42))
    }

    @Test
    fun `first position after now skips reads already past in the window`() {
        val schedule = MeterSchedule(1000, 900)
        val pos = schedule.firstPositionAtOrAfter(0, 450_000)
        assertTrue(schedule.dueMillis(0, pos, 0) >= 450_000)
        assertTrue(schedule.dueMillis(0, pos - 1, 0) < 450_000)
    }
}
