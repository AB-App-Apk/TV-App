package com.example.tvremote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

const val TV_PORT = 8765

/** What came back from one request to the TV. */
sealed interface TvReply {
    data class Ok(val body: String) : TvReply
    data class Rejected(val http: Int) : TvReply
    data object Unreachable : TvReply
}

/** Network wrapper: one PIN-protected HTTP request to the TV Dash server. Never throws; failures become [TvReply]. */
class TvLink(private val host: () -> String, private val pin: () -> String) {
    suspend fun get(path: String): TvReply = withContext(Dispatchers.IO) {
        val address = host()
        if (address.isBlank()) return@withContext TvReply.Unreachable
        val sep = if ('?' in path) '&' else '?'
        try {
            val c = URL("http://$address:$TV_PORT/$path${sep}pin=${pin()}").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 2_000
                c.readTimeout = 4_000
                if (c.responseCode == HttpURLConnection.HTTP_OK) {
                    TvReply.Ok(c.inputStream.bufferedReader().use { it.readText() })
                } else {
                    TvReply.Rejected(c.responseCode)
                }
            } finally {
                c.disconnect()
            }
        } catch (e: IOException) {
            TvReply.Unreachable
        }
    }
}

/**
 * Strict FIFO. One consumer coroutine drains the channel, so commands run one at a time in the order they were
 * submitted and a later command can never overtake an earlier one.
 */
class CommandQueue(scope: CoroutineScope) {
    private val jobs = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (job in jobs) {
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failing job must not stop the queue; jobs report their own errors.
                }
            }
        }
    }

    fun submit(job: suspend () -> Unit) { jobs.trySend(job) }
    fun close() { jobs.close() }
}
