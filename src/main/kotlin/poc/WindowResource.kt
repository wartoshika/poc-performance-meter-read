package poc

import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/** Measurement window used by scripts/run-scenario.py. */
@Path("/poc")
@Produces(MediaType.APPLICATION_JSON)
class WindowResource(private val stats: ReadStats, private val config: PocConfig) {
    @GET
    @Path("/window")
    fun window(): Map<String, Any?> = stats.window()

    @POST
    @Path("/window/reset")
    fun reset(): Map<String, Any?> {
        stats.resetWindow()
        return mapOf("reset" to true)
    }

    @GET
    @Path("/config")
    fun config(): Map<String, Any?> = mapOf(
        "meterCount" to config.meterCount(),
        "intervalSeconds" to config.intervalSeconds(),
        "targetRate" to config.meterCount().toDouble() / config.intervalSeconds(),
        "rateLimit" to config.rateLimit(),
        "rampUpSeconds" to config.rampUpSeconds(),
        "targetUrl" to config.targetUrl(),
        "client" to config.client(),
        "httpVersion" to config.httpVersion(),
        "http2MaxConnections" to config.http2MaxConnections(),
        "maxConnections" to config.maxConnections(),
        "timeoutSeconds" to config.timeoutSeconds(),
        "maxResponseBytes" to config.maxResponseBytes(),
        "dbEnabled" to config.dbEnabled(),
        "javaVersion" to System.getProperty("java.version"),
        "javaVmVersion" to System.getProperty("java.vm.version"),
        "kotlinVersion" to KotlinVersion.CURRENT.toString(),
        "quarkusVersion" to io.quarkus.runtime.Quarkus::class.java.`package`.implementationVersion,
        "jvmArgs" to java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments,
    )
}
