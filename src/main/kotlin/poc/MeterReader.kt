package poc

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.logging.Log
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import java.time.Instant
import java.util.concurrent.Semaphore

/**
 * One read = one blocking exchange on the calling virtual thread.
 */
@Singleton
class MeterReader(
    private val config: PocConfig,
    private val stats: ReadStats,
    private val store: ReadingStore,
    private val mapper: ObjectMapper,
) {
    private val accessPoint: AccessPoint = when (config.client()) {
        "quarkus" -> QuarkusAccessPoint(config, stats)
        "jdk" -> JdkAccessPoint(config, stats)
        else -> error("poc.client must be quarkus or jdk, was ${config.client()}")
    }
    private val permits: Semaphore? =
        if (config.httpVersion() == "HTTP_1_1") Semaphore(config.maxConnections(), true) else null

    init {
        Log.infof("HTTP client: %s %s maxConnections=%s http2MaxConnections=%s timeout=%ds maxResponseBytes=%d target=%s",
            config.client(), config.httpVersion(), permits?.let { config.maxConnections() } ?: "n/a",
            if (permits == null && config.client() == "quarkus") config.http2MaxConnections() else "n/a",
            config.timeoutSeconds(), config.maxResponseBytes(), config.targetUrl())
    }

    fun read(meterNo: Int, dueMillis: Double) {
        val meterId = MeterSchedule.meterId(meterNo)
        val dueAt = Instant.ofEpochMilli(dueMillis.toLong())
        stats.readStarted((MonotonicClock.millis() - dueMillis).toLong())
        val t0 = System.nanoTime()
        var body: ByteArray? = null
        val outcome = try {
            val response = exchange(meterId, """{"meterNumber":"$meterId","dueAt":"$dueAt"}""")
            when (response.status) {
                200 -> {
                    body = response.body
                    Outcome.OK
                }
                503 -> Outcome.HTTP_503
                else -> Outcome.HTTP_OTHER
            }
        } catch (e: ReadFailure) {
            e.outcome
        } catch (e: Exception) {
            Log.debugf(e, "read %s failed", meterId)
            Outcome.IO_ERROR
        }
        val reading = if (outcome == Outcome.OK) parse(meterId, body!!) else null
        val finalOutcome = if (outcome == Outcome.OK && reading == null) Outcome.INVALID_BODY else outcome
        stats.readCompleted(meterId, finalOutcome, System.nanoTime() - t0)
        if (reading != null && config.dbEnabled()) store.write(meterId, dueAt, reading)
    }

    private fun exchange(meterId: String, body: String): Exchange {
        val sem = permits ?: return accessPoint.post(meterId, body)
        if (!sem.tryAcquire()) {
            stats.poolPending.incrementAndGet()
            val w0 = System.nanoTime()
            try {
                sem.acquire()
            } finally {
                stats.poolPending.decrementAndGet()
            }
            stats.poolAcquired(System.nanoTime() - w0)
        } else {
            stats.poolAcquired(0)
        }
        try {
            return accessPoint.post(meterId, body)
        } finally {
            stats.poolInUse.decrementAndGet()
            sem.release()
        }
    }

    private fun parse(meterId: String, body: ByteArray): MeterReading? = try {
        val node = mapper.readTree(body)
        if (node.path("meterNumber").asText() != meterId) null
        else MeterReading(
            value = node.path("reading").path("value").decimalValue(),
            readAt = Instant.parse(node.path("timestamp").asText()),
            statusCode = node.path("statusCode").asText(),
        )
    } catch (e: Exception) {
        null
    }

    @PreDestroy
    fun close() {
        accessPoint.close()
    }
}

data class MeterReading(val value: java.math.BigDecimal, val readAt: Instant, val statusCode: String)
