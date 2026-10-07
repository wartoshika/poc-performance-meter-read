package poc

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName

@ConfigMapping(prefix = "poc")
interface PocConfig {
    /** Number of meters. 360,000 meters at a 900 s interval give 400 reads/s. */
    fun meterCount(): Int

    fun intervalSeconds(): Long

    /** Global limit of read starts per second. Reads above it are deferred, never dropped. */
    fun rateLimit(): Int

    /** Linear ramp from 0 to the full rate. Reads thinned out during the ramp are skipped and counted. */
    fun rampUpSeconds(): Long

    fun schedulerEnabled(): Boolean

    /** The single access point, one host:port. */
    fun targetUrl(): String

    /** quarkus (Quarkus REST client on the Vert.x HTTP client, the default) or jdk (java.net.http, comparison). */
    fun client(): String

    /** HTTP_1_1 or HTTP_2. */
    fun httpVersion(): String

    /** Upper bound of HTTP/2 connections of the Quarkus client (Vert.x http2MaxPoolSize). */
    fun http2MaxConnections(): Int

    /**
     * Upper bound of concurrent HTTP/1.1 exchanges, enforced by a semaphore around the exchange so
     * that pending acquires are measurable; with HTTP/1.1 one exchange occupies one connection.
     * The Quarkus client's pool (Vert.x maxPoolSize) gets the same size, so it never queues itself.
     * The JDK client has no connection limit of its own. Not applied to HTTP/2.
     */
    fun maxConnections(): Int

    fun timeoutSeconds(): Long

    fun maxResponseBytes(): Int

    fun lateThresholdMillis(): Long

    fun dbEnabled(): Boolean

    fun analysis(): Analysis

    interface Analysis {
        fun injectedTimeoutPattern(): String

        @WithName("injected-503-pattern")
        fun injected503Pattern(): String
    }
}
