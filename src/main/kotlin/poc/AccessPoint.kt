package poc

/** The single access point endpoint, POST /meters/{meterId}/reading. Blocks the calling (virtual) thread. */
interface AccessPoint : AutoCloseable {
    fun post(meterId: String, body: String): Exchange
}

class Exchange(val status: Int, val body: ByteArray?)

/** A failed exchange, already classified. */
class ReadFailure(val outcome: Outcome, cause: Throwable? = null) : Exception(outcome.tag, cause)
