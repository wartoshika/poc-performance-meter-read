package poc

import io.quarkus.logging.Log
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import jakarta.annotation.Priority
import jakarta.enterprise.event.Observes
import jakarta.inject.Singleton
import java.util.ArrayDeque
import java.util.concurrent.locks.LockSupport

/**
 * Issues due reads at the rate that follows from the meter count, one virtual thread per read.
 *
 * A single platform thread walks the phase-sorted schedule. A token bucket (refill L/s, capacity L)
 * enforces the global limit of read starts per second; a read without a token waits in a FIFO
 * queue (deferred, never dropped) and is counted once.
 */
@Singleton
class ReadScheduler(
    private val config: PocConfig,
    private val reader: MeterReader,
    private val stats: ReadStats,
) {
    private class Due(val meterNo: Int, val dueMillis: Double, val enqueuedIteration: Long)

    @Volatile private var running = false
    private var thread: Thread? = null

    fun onStart(@Observes @Priority(200) event: StartupEvent) {
        if (!config.schedulerEnabled()) {
            Log.info("scheduler disabled")
            return
        }
        val schedule = MeterSchedule(config.meterCount(), config.intervalSeconds())
        Log.infof("scheduler: %d meters, interval %d s => %.1f reads/s, limit %d/s, ramp-up %d s",
            config.meterCount(), config.intervalSeconds(), config.meterCount().toDouble() / config.intervalSeconds(),
            config.rateLimit(), config.rampUpSeconds())
        running = true
        thread = Thread.ofPlatform().name("read-scheduler").start { loop(schedule) }
    }

    fun onStop(@Observes event: ShutdownEvent) {
        running = false
        thread?.join(2000)
    }

    private fun loop(schedule: MeterSchedule) {
        val intervalMillis = (schedule.intervalMillis).toLong()
        val startMillis = MonotonicClock.millis().toLong()
        val windowStart = startMillis - Math.floorMod(startMillis, intervalMillis)
        var position = schedule.firstPositionAtOrAfter(windowStart, startMillis)
        var cycle = 0L
        if (position == schedule.size) {
            position = 0
            cycle = 1
        }
        val rampMillis = config.rampUpSeconds() * 1000.0
        val limit = config.rateLimit().toDouble()
        // Burst capacity of one second worth of tokens. With a smaller bucket and a limit equal to
        // the target rate, a single stall of the scheduler thread loses refill while the bucket is
        // full, and the resulting backlog never drains because supply equals demand.
        val capacity = maxOf(1.0, limit)
        var tokens = capacity
        var lastRefill = System.nanoTime()
        var rampAccumulator = 0.0
        val pending = ArrayDeque<Due>()
        val virtual = Thread.ofVirtual().name("read-", 0).factory()
        var iteration = 0L

        while (running) {
            iteration++
            val now = MonotonicClock.millis()
            // 1. Collect everything that is due by now.
            while (true) {
                val due = schedule.dueMillis(windowStart, position, cycle)
                if (due > now) break
                val meterNo = schedule.meterAt(position)
                val rampFraction = if (rampMillis <= 0) 1.0 else ((now - startMillis) / rampMillis).coerceAtMost(1.0)
                rampAccumulator += rampFraction
                if (rampAccumulator >= 1.0) {
                    rampAccumulator -= 1.0
                    pending.addLast(Due(meterNo, due, iteration))
                } else {
                    stats.skipped()
                }
                if (++position == schedule.size) {
                    position = 0
                    cycle++
                }
            }
            // 2. Refill the token bucket and start as many reads as the limit allows.
            val nanos = System.nanoTime()
            tokens = minOf(capacity, tokens + (nanos - lastRefill) / 1e9 * limit)
            lastRefill = nanos
            while (tokens >= 1.0 && pending.isNotEmpty()) {
                tokens -= 1.0
                val d = pending.removeFirst()
                if (d.enqueuedIteration != iteration) stats.deferred()
                virtual.newThread { reader.read(d.meterNo, d.dueMillis) }.start()
            }
            // 3. Sleep until the next due read or the next token, at most 1 ms.
            val untilNextDue = schedule.dueMillis(windowStart, position, cycle) - MonotonicClock.millis()
            val sleepMillis = if (pending.isNotEmpty()) 1000.0 / limit else untilNextDue
            LockSupport.parkNanos((sleepMillis.coerceIn(0.05, 1.0) * 1_000_000).toLong())
        }
    }
}
