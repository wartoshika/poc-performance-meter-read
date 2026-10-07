package poc

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.Flow

class ResponseTooLargeException(limit: Int) : IOException("response body exceeds $limit bytes")

/**
 * Comparison client: java.net.http.HttpClient with a virtual thread executor. Over cleartext it
 * reaches HTTP/2 only through the h2c Upgrade mechanism, and it has no connection limit.
 */
class JdkAccessPoint(private val config: PocConfig, private val stats: ReadStats) : AccessPoint {
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.valueOf(config.httpVersion()))
        .executor(executor)
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val timeout = Duration.ofSeconds(config.timeoutSeconds())
    private val maxBytes = config.maxResponseBytes()
    private val baseUrl = config.targetUrl().trimEnd('/')

    override fun post(meterId: String, body: String): Exchange {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/meters/$meterId/reading"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        try {
            val response = client.send(request, ::limitedBody)
            stats.responseVersion(response.version().name)
            return Exchange(response.statusCode(), response.body())
        } catch (e: HttpConnectTimeoutException) {
            throw ReadFailure(Outcome.CONNECT_ERROR, e)
        } catch (e: HttpTimeoutException) {
            throw ReadFailure(Outcome.TIMEOUT, e)
        } catch (e: ResponseTooLargeException) {
            throw ReadFailure(Outcome.TOO_LARGE, e)
        } catch (e: ConnectException) {
            throw ReadFailure(Outcome.CONNECT_ERROR, e)
        } catch (e: IOException) {
            throw ReadFailure(if (e.cause is ResponseTooLargeException) Outcome.TOO_LARGE else Outcome.IO_ERROR, e)
        }
    }

    private fun limitedBody(info: HttpResponse.ResponseInfo): HttpResponse.BodySubscriber<ByteArray> {
        val declared = info.headers().firstValueAsLong("content-length")
        if (declared.isPresent && declared.asLong > maxBytes) {
            return FailingSubscriber(ResponseTooLargeException(maxBytes))
        }
        return LimitedBodySubscriber(maxBytes)
    }

    override fun close() {
        client.shutdownNow()
        executor.shutdownNow()
    }
}

/** Collects the body and fails as soon as it grows past [limit]; the exchange is cancelled. */
private class LimitedBodySubscriber(private val limit: Int) : HttpResponse.BodySubscriber<ByteArray> {
    private val result = CompletableFuture<ByteArray>()
    private val buffer = ByteArrayOutputStream(2048)
    private lateinit var subscription: Flow.Subscription

    override fun getBody(): CompletionStage<ByteArray> = result

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription = subscription
        subscription.request(Long.MAX_VALUE)
    }

    override fun onNext(item: List<ByteBuffer>) {
        if (result.isDone) return
        for (b in item) {
            if (buffer.size() + b.remaining() > limit) {
                subscription.cancel()
                result.completeExceptionally(ResponseTooLargeException(limit))
                return
            }
            val bytes = ByteArray(b.remaining())
            b.get(bytes)
            buffer.write(bytes)
        }
    }

    override fun onError(throwable: Throwable) {
        result.completeExceptionally(throwable)
    }

    override fun onComplete() {
        result.complete(buffer.toByteArray())
    }
}

private class FailingSubscriber(private val error: Throwable) : HttpResponse.BodySubscriber<ByteArray> {
    private val result = CompletableFuture<ByteArray>().apply { completeExceptionally(error) }

    override fun getBody(): CompletionStage<ByteArray> = result

    override fun onSubscribe(subscription: Flow.Subscription) {
        subscription.cancel()
    }

    override fun onNext(item: List<ByteBuffer>) {}

    override fun onError(throwable: Throwable) {}

    override fun onComplete() {}
}
