package poc

/**
 * Due times as the phase model defines them: meter n (1-based) has the phase frac(n * 0.6180339887),
 * and is due at windowStart + (phase + k) * interval.
 *
 * The meters are kept sorted by phase so that walking the array in order yields the due reads
 * of one interval in time order; after the last meter the walk wraps to the next interval.
 */
class MeterSchedule(meterCount: Int, intervalSeconds: Long) {
    val intervalMillis: Double = intervalSeconds * 1000.0

    /** Meter numbers (1-based) sorted by phase. */
    private val meters: IntArray

    /** Phases in the same order as [meters]. */
    private val phases: DoubleArray

    init {
        require(meterCount > 0) { "meterCount must be positive" }
        val keyed = LongArray(meterCount)
        val phaseOf = DoubleArray(meterCount + 1)
        for (n in 1..meterCount) {
            val p = phase(n)
            phaseOf[n] = p
            // Sort key: phase scaled to 2^31 in the high bits, meter number in the low bits.
            keyed[n - 1] = ((p * (1L shl 31)).toLong() shl 32) or n.toLong()
        }
        keyed.sort()
        meters = IntArray(meterCount) { (keyed[it] and 0xFFFFFFFFL).toInt() }
        phases = DoubleArray(meterCount) { phaseOf[meters[it]] }
    }

    val size: Int get() = meters.size

    fun meterAt(position: Int): Int = meters[position]

    fun dueMillis(windowStartMillis: Long, position: Int, cycle: Long): Double =
        windowStartMillis + (phases[position] + cycle) * intervalMillis

    /** First position whose due time in cycle 0 is at or after [nowMillis]. */
    fun firstPositionAtOrAfter(windowStartMillis: Long, nowMillis: Long): Int {
        var lo = 0
        var hi = phases.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (dueMillis(windowStartMillis, mid, 0) < nowMillis) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        const val GOLDEN = 0.6180339887

        fun phase(n: Int): Double {
            val x = n * GOLDEN
            return x - Math.floor(x)
        }

        fun meterId(n: Int): String = "MTR" + n.toString().padStart(9, '0')
    }
}

/**
 * Wall-clock milliseconds derived from the monotonic clock, anchored once at class load. Due times
 * and start lag use it, so an NTP adjustment of the wall clock cannot fake a burst or a stall.
 */
object MonotonicClock {
    private val baseNanos = System.nanoTime()
    private val baseMillis = System.currentTimeMillis()

    fun millis(): Double = baseMillis + (System.nanoTime() - baseNanos) / 1e6
}
