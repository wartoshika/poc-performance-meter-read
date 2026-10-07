package poc

import io.quarkus.logging.Log
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpVersion
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientResponseContext
import jakarta.ws.rs.client.ClientResponseFilter
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ContextResolver
import org.jboss.resteasy.reactive.client.api.QuarkusRestClientProperties
import org.jboss.resteasy.reactive.client.impl.ClientRequestContextImpl
import java.net.ConnectException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

@Path("/meters")
interface AccessPointClient : AutoCloseable {
    @POST
    @Path("/{meterId}/reading")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun read(@PathParam("meterId") meterId: String, body: String): Response
}

/**
 * Default client: the Quarkus REST client on the Vert.x HTTP client. The blocking interface is
 * called from the read's virtual thread; the I/O runs on the Vert.x event loops.
 *
 * HTTP/2 over cleartext uses prior knowledge (no h2c Upgrade round trip). Vert.x opens another
 * HTTP/2 connection when the existing ones reach the server's SETTINGS_MAX_CONCURRENT_STREAMS,
 * up to [PocConfig.http2MaxConnections].
 */
class QuarkusAccessPoint(private val config: PocConfig, private val stats: ReadStats) : AccessPoint {
    private val maxBytes = config.maxResponseBytes()
    private val loggedFailures = AtomicInteger()
    private val http2 = config.httpVersion() == "HTTP_2"

    val options: HttpClientOptions = HttpClientOptions()
        .setProtocolVersion(if (http2) HttpVersion.HTTP_2 else HttpVersion.HTTP_1_1)
        .setHttp2ClearTextUpgrade(false)
        .setMaxPoolSize(config.maxConnections())
        .setHttp2MaxPoolSize(config.http2MaxConnections())
        .setMaxWaitQueueSize(-1)
        .setKeepAlive(true)
        .setKeepAliveTimeout(60)
        .setHttp2KeepAliveTimeout(60)
        .setPipelining(false)
        .setConnectTimeout(10_000)

    private val client: AccessPointClient = QuarkusRestClientBuilder.newBuilder()
        .baseUri(URI.create(config.targetUrl()))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds(), TimeUnit.SECONDS)
        .disableDefaultMapper(true)
        // The REST client applies its own pool size (default 50) over the Vert.x options above.
        .property(QuarkusRestClientProperties.CONNECTION_POOL_SIZE,
            if (http2) config.http2MaxConnections() else config.maxConnections())
        .property(QuarkusRestClientProperties.CONNECTION_TTL, 60)
        .property(QuarkusRestClientProperties.KEEP_ALIVE_ENABLED, true)
        .property(QuarkusRestClientProperties.HTTP2, http2)
        .register(OptionsResolver(options))
        .register(VersionFilter(stats))
        .build(AccessPointClient::class.java)

    override fun post(meterId: String, body: String): Exchange {
        try {
            client.read(meterId, body).use { r ->
                if (r.length > maxBytes) throw ReadFailure(Outcome.TOO_LARGE)
                if (r.status != 200) return Exchange(r.status, null)
                val bytes = r.readEntity(ByteArray::class.java)
                if (bytes.size > maxBytes) throw ReadFailure(Outcome.TOO_LARGE)
                return Exchange(r.status, bytes)
            }
        } catch (e: ReadFailure) {
            throw e
        } catch (e: Exception) {
            val outcome = classify(e)
            if (outcome == Outcome.IO_ERROR && loggedFailures.incrementAndGet() <= 20) {
                Log.warnf("read %s failed: %s", meterId, causes(e))
            }
            throw ReadFailure(outcome, e)
        }
    }

    private fun classify(e: Throwable): Outcome {
        val chain = generateSequence(e) { it.cause }.toList()
        return when {
            chain.any { it is ConnectException || it.javaClass.name.contains("ConnectTimeout") } -> Outcome.CONNECT_ERROR
            chain.any { it is TimeoutException || it.javaClass.simpleName.contains("Timeout") } -> Outcome.TIMEOUT
            else -> Outcome.IO_ERROR
        }
    }

    private fun causes(e: Throwable) = generateSequence(e) { it.cause }.joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }

    override fun close() {
        client.close()
    }
}

private class OptionsResolver(private val options: HttpClientOptions) : ContextResolver<HttpClientOptions> {
    override fun getContext(type: Class<*>?): HttpClientOptions = options
}

/** Records the negotiated protocol of every response; JAX-RS itself does not expose it. */
private class VersionFilter(private val stats: ReadStats) : ClientResponseFilter {
    override fun filter(request: ClientRequestContext, response: ClientResponseContext) {
        val version = (request as? ClientRequestContextImpl)?.restClientRequestContext?.vertxClientResponse?.version()
        if (version != null) stats.responseVersion(version.name)
    }
}
