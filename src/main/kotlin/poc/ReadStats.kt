package poc

import com.sun.management.GarbageCollectionNotificationInfo
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.inject.Singleton
import org.HdrHistogram.Histogram
import org.HdrHistogram.Recorder
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import javax.management.NotificationEmitter
import javax.management.openmbean.CompositeData

enum class Outcome(val tag: String) {
    OK("ok"),
    HTTP_503("http_503"),
    HTTP_OTHER("http_other"),
    TIMEOUT("timeout"),
    CONNECT_ERROR("connect_error"),
    TOO_LARGE("too_large"),
    INVALID_BODY("invalid_body"),
    IO_ERROR("io_error"),
}

/**
 * Two views of the same events: Micrometer meters for live monitoring (/q/metrics), and a
 * measurement window with exact HdrHistogram percentiles that the scenario driver resets after
 * the ramp-up and reads at the end (/poc/window).
 */
@Singleton
class ReadStats(private val registry: MeterRegistry, private val config: PocConfig) {
    private val injectedTimeout = Regex(config.analysis().injectedTimeoutPattern())
    private val injected503 = Regex(config.analysis().injected503Pattern())

    // Micrometer
    private val startedCounter = Counter.builder("poc.reads.started").register(registry)
    private val completedCounters = Outcome.entries.associateWith {
        Counter.builder("poc.reads.completed").tag("outcome", it.tag).register(registry)
    }
    private val latencyTimers = Outcome.entries.associateWith {
        Timer.builder("poc.read.latency")
            .tag("outcome", it.tag)
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .minimumExpectedValue(java.time.Duration.ofMillis(1))
            .maximumExpectedValue(java.time.Duration.ofSeconds(60))
            .register(registry)
    }
    private val deferredCounter = Counter.builder("poc.reads.deferred").register(registry)
    private val lateCounter = Counter.builder("poc.reads.late").register(registry)
    private val skippedCounter = Counter.builder("poc.reads.skipped.rampup").register(registry)
    private val dbCounters = listOf("ok", "duplicate", "error").associateWith {
        Counter.builder("poc.db.writes").tag("outcome", it).register(registry)
    }
    private val dbTimer = Timer.builder("poc.db.write.latency")
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(registry)
    private val poolWaitTimer = Timer.builder("poc.http.pool.wait")
        .publishPercentiles(0.5, 0.99)
        .register(registry)

    private val wResponseVersions = java.util.concurrent.ConcurrentHashMap<String, LongAdder>()

    fun responseVersion(version: String) {
        registry.counter("poc.http.responses", "version", version).increment()
        wResponseVersions.computeIfAbsent(version) { LongAdder() }.increment()
    }

    val open = AtomicInteger()
    val poolInUse = AtomicInteger()
    val poolPending = AtomicInteger()

    init {
        Gauge.builder("poc.reads.open", open) { it.get().toDouble() }.register(registry)
        Gauge.builder("poc.http.pool.in_use", poolInUse) { it.get().toDouble() }.register(registry)
        Gauge.builder("poc.http.pool.pending", poolPending) { it.get().toDouble() }.register(registry)
    }

    // Measurement window
    @Volatile private var windowStartNanos = System.nanoTime()
    @Volatile private var windowStartEpochMillis = System.currentTimeMillis()
    private val wStarted = LongAdder()
    private val wCompleted = Outcome.entries.associateWith { LongAdder() }
    private val wInjected = Outcome.entries.associateWith { LongAdder() }
    private val wDeferred = LongAdder()
    private val wLate = LongAdder()
    private val wSkipped = LongAdder()
    private val wDbOk = LongAdder()
    private val wDbDuplicate = LongAdder()
    private val wDbError = LongAdder()
    private val wOpenMax = AtomicInteger()
    private val wPoolInUseMax = AtomicInteger()
    private val wPoolPendingMax = AtomicInteger()
    private val wHeapUsedMax = AtomicLong()
    private val wHeapCommittedMax = AtomicLong()
    private val wPlatformThreadsMax = AtomicInteger()
    private val wGcPauseCount = LongAdder()
    private val wGcPauseTotalMillis = LongAdder()
    private val wGcPauseMaxMillis = AtomicLong()
    private val heapSamples = ConcurrentLinkedQueue<LongArray>()

    private val latencyAll = Recorder(3)
    private val latencyOk = Recorder(3)
    private val startLag = Recorder(3)
    private val dbLatency = Recorder(3)
    private val accAll = Histogram(3)
    private val accOk = Histogram(3)
    private val accLag = Histogram(3)
    private val accDb = Histogram(3)

    private val sampler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "stats-sampler").apply { isDaemon = true }
    }

    init {
        sampler.scheduleAtFixedRate(::sample, 1, 1, TimeUnit.SECONDS)
        for (gc in ManagementFactory.getGarbageCollectorMXBeans()) {
            (gc as? NotificationEmitter)?.addNotificationListener({ n, _ ->
                if (n.type == GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION) {
                    val info = GarbageCollectionNotificationInfo.from(n.userData as CompositeData)
                    // Concurrent cycles (G1 Concurrent GC, ZGC Cycles) are not pauses.
                    if (!info.gcName.contains("Concurrent") && !info.gcName.contains("Cycles")) {
                        val ms = info.gcInfo.duration
                        wGcPauseCount.increment()
                        wGcPauseTotalMillis.add(ms)
                        wGcPauseMaxMillis.accumulateAndGet(ms, ::maxOf)
                    }
                }
            }, null, null)
        }
    }

    private fun sample() {
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        wHeapUsedMax.accumulateAndGet(heap.used, ::maxOf)
        wHeapCommittedMax.accumulateAndGet(heap.committed, ::maxOf)
        wPlatformThreadsMax.accumulateAndGet(ManagementFactory.getThreadMXBean().threadCount, ::maxOf)
        wOpenMax.accumulateAndGet(open.get(), ::maxOf)
        wPoolInUseMax.accumulateAndGet(poolInUse.get(), ::maxOf)
        wPoolPendingMax.accumulateAndGet(poolPending.get(), ::maxOf)
        heapSamples.add(longArrayOf(System.currentTimeMillis(), heap.used))
    }

    fun readStarted(lagMillis: Long) {
        startedCounter.increment()
        wStarted.increment()
        startLag.recordValue(lagMillis.coerceIn(0, 3_600_000))
        if (lagMillis > config.lateThresholdMillis()) {
            lateCounter.increment()
            wLate.increment()
        }
        wOpenMax.accumulateAndGet(open.incrementAndGet(), ::maxOf)
    }

    fun readCompleted(meterId: String, outcome: Outcome, nanos: Long) {
        open.decrementAndGet()
        completedCounters.getValue(outcome).increment()
        latencyTimers.getValue(outcome).record(nanos, TimeUnit.NANOSECONDS)
        wCompleted.getValue(outcome).increment()
        if (isInjected(meterId, outcome)) wInjected.getValue(outcome).increment()
        val micros = TimeUnit.NANOSECONDS.toMicros(nanos).coerceAtLeast(1)
        latencyAll.recordValue(micros)
        if (outcome == Outcome.OK) latencyOk.recordValue(micros)
    }

    private fun isInjected(meterId: String, outcome: Outcome) = when (outcome) {
        Outcome.TIMEOUT -> injectedTimeout.matches(meterId)
        Outcome.HTTP_503 -> injected503.matches(meterId)
        else -> false
    }

    fun deferred() {
        deferredCounter.increment()
        wDeferred.increment()
    }

    fun skipped() {
        skippedCounter.increment()
        wSkipped.increment()
    }

    fun poolAcquired(waitNanos: Long) {
        poolWaitTimer.record(waitNanos, TimeUnit.NANOSECONDS)
        wPoolInUseMax.accumulateAndGet(poolInUse.incrementAndGet(), ::maxOf)
    }

    fun dbWrite(result: String, nanos: Long) {
        dbCounters.getValue(result).increment()
        dbTimer.record(nanos, TimeUnit.NANOSECONDS)
        when (result) {
            "ok" -> wDbOk.increment()
            "duplicate" -> wDbDuplicate.increment()
            else -> wDbError.increment()
        }
        dbLatency.recordValue(TimeUnit.NANOSECONDS.toMicros(nanos).coerceAtLeast(1))
    }

    @Synchronized
    fun resetWindow() {
        listOf(latencyAll, latencyOk, startLag, dbLatency).forEach { it.reset() }
        listOf(accAll, accOk, accLag, accDb).forEach { it.reset() }
        listOf(wStarted, wDeferred, wLate, wSkipped, wDbOk, wDbDuplicate, wDbError, wGcPauseCount, wGcPauseTotalMillis)
            .forEach { it.reset() }
        wCompleted.values.forEach { it.reset() }
        wInjected.values.forEach { it.reset() }
        wResponseVersions.clear()
        wOpenMax.set(open.get())
        wPoolInUseMax.set(poolInUse.get())
        wPoolPendingMax.set(poolPending.get())
        wHeapUsedMax.set(0)
        wHeapCommittedMax.set(0)
        wPlatformThreadsMax.set(0)
        wGcPauseMaxMillis.set(0)
        heapSamples.clear()
        windowStartNanos = System.nanoTime()
        windowStartEpochMillis = System.currentTimeMillis()
    }

    @Synchronized
    fun window(): Map<String, Any?> {
        accAll.add(latencyAll.intervalHistogram)
        accOk.add(latencyOk.intervalHistogram)
        accLag.add(startLag.intervalHistogram)
        accDb.add(dbLatency.intervalHistogram)
        val seconds = (System.nanoTime() - windowStartNanos) / 1e9
        val completed = wCompleted.mapValues { it.value.sum() }
        val totalCompleted = completed.values.sum()
        val injected = wInjected.mapValues { it.value.sum() }
        val unexpected = Outcome.entries.filter { it != Outcome.OK }
            .associateWith { completed.getValue(it) - injected.getValue(it) }
        return linkedMapOf(
            "windowStartEpochMillis" to windowStartEpochMillis,
            "windowSeconds" to seconds,
            "started" to wStarted.sum(),
            "startedPerSecond" to wStarted.sum() / seconds,
            "completed" to totalCompleted,
            "completedPerSecond" to totalCompleted / seconds,
            "completedByOutcome" to completed.mapKeys { it.key.tag },
            "injectedByOutcome" to injected.filterValues { it > 0 }.mapKeys { it.key.tag },
            "unexpectedErrors" to unexpected.values.sum(),
            "unexpectedByOutcome" to unexpected.filterValues { it > 0 }.mapKeys { it.key.tag },
            "responsesByHttpVersion" to wResponseVersions.mapValues { it.value.sum() },
            "deferred" to wDeferred.sum(),
            "late" to wLate.sum(),
            "skippedRampUp" to wSkipped.sum(),
            "openNow" to open.get(),
            "openMax" to wOpenMax.get(),
            "poolInUseMax" to wPoolInUseMax.get(),
            "poolPendingMax" to wPoolPendingMax.get(),
            "latencyOkMillis" to percentiles(accOk),
            "latencyAllMillis" to percentiles(accAll),
            "startLagMillis" to lagPercentiles(accLag),
            "heapUsedMaxBytes" to wHeapUsedMax.get(),
            "heapCommittedMaxBytes" to wHeapCommittedMax.get(),
            "heapMaxBytes" to Runtime.getRuntime().maxMemory(),
            "heapFloorPerMinuteMiB" to heapFloors(),
            "heapFloorSlopeMiBPerMinute" to slope(heapFloors()),
            "platformThreadsMax" to wPlatformThreadsMax.get(),
            "gcPauseCount" to wGcPauseCount.sum(),
            "gcPauseTotalMillis" to wGcPauseTotalMillis.sum(),
            "gcPauseMaxMillis" to wGcPauseMaxMillis.get(),
            "gc" to ManagementFactory.getGarbageCollectorMXBeans().map { it.name },
            "availableProcessors" to Runtime.getRuntime().availableProcessors(),
            "db" to mapOf(
                "ok" to wDbOk.sum(),
                "duplicate" to wDbDuplicate.sum(),
                "error" to wDbError.sum(),
                "latencyMillis" to percentiles(accDb),
            ),
        )
    }

    private fun percentiles(h: Histogram): Map<String, Any> = mapOf(
        "count" to h.totalCount,
        "p50" to h.getValueAtPercentile(50.0) / 1000.0,
        "p95" to h.getValueAtPercentile(95.0) / 1000.0,
        "p99" to h.getValueAtPercentile(99.0) / 1000.0,
        "max" to h.maxValue / 1000.0,
    )

    private fun lagPercentiles(h: Histogram): Map<String, Any> = mapOf(
        "count" to h.totalCount,
        "p50" to h.getValueAtPercentile(50.0),
        "p99" to h.getValueAtPercentile(99.0),
        "p999" to h.getValueAtPercentile(99.9),
        "max" to h.maxValue,
    )

    /**
     * Lowest sampled heap occupancy per full minute of the window. The floor of a saw-tooth is
     * what is left after collections, so an upward trend of the floors is a leak signal.
     */
    private fun heapFloors(): List<Double> {
        val points = heapSamples.toList()
        if (points.isEmpty()) return emptyList()
        val t0 = windowStartEpochMillis
        return points.groupBy { (it[0] - t0) / 60_000 }
            .filterKeys { it < (points.last()[0] - t0) / 60_000 }
            .toSortedMap()
            .values.map { minute -> minute.minOf { it[1] } / 1048576.0 }
    }

    /** Least-squares slope of equally spaced values, in units per step. */
    private fun slope(ys: List<Double>): Double? {
        if (ys.size < 3) return null
        val mx = (ys.size - 1) / 2.0
        val my = ys.average()
        val num = ys.indices.sumOf { (it - mx) * (ys[it] - my) }
        val den = ys.indices.sumOf { (it - mx) * (it - mx) }
        return num / den
    }
}
