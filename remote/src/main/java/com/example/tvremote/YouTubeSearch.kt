package com.example.tvremote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Why a search could not be opened on the TV. */
enum class SearchFailure(val retryable: Boolean) {
    EMPTY(false), UNREACHABLE(true), WRONG_PIN(false), NO_YOUTUBE(false), NOT_HANDLED(false), NO_OVERLAY(true), UNKNOWN(true)
}

/** Everything the live status line can show. */
sealed interface SearchState {
    data object Idle : SearchState
    data class Queued(val query: String, val ahead: Int) : SearchState
    data class Sending(val query: String, val attempt: Int, val maxAttempts: Int) : SearchState
    data class Opened(val query: String) : SearchState
    data class Failed(val query: String, val reason: SearchFailure) : SearchState
}

/** The single place that knows the reply vocabulary of the TV's /yt endpoint. */
object SearchReplyMapper {
    fun map(query: String, reply: TvReply): SearchState = when (reply) {
        TvReply.Unreachable -> SearchState.Failed(query, SearchFailure.UNREACHABLE)
        is TvReply.Rejected ->
            SearchState.Failed(query, if (reply.http == 403) SearchFailure.WRONG_PIN else SearchFailure.UNKNOWN)
        is TvReply.Ok -> when (reply.body.trim()) {
            "ok" -> SearchState.Opened(query)
            "empty" -> SearchState.Failed(query, SearchFailure.EMPTY)
            "no-youtube" -> SearchState.Failed(query, SearchFailure.NO_YOUTUBE)
            "not-handled" -> SearchState.Failed(query, SearchFailure.NOT_HANDLED)
            "no-overlay" -> SearchState.Failed(query, SearchFailure.NO_OVERLAY)
            else -> SearchState.Failed(query, SearchFailure.UNKNOWN)
        }
    }
}

/**
 * Turns the phone's text box into a YouTube search on the TV. The text box stays the source of truth: the screen
 * hands its current content to [submit], which queues exactly one command. Nothing is typed key by key.
 */
class YouTubeSearchController(private val link: TvLink, private val queue: CommandQueue) {
    private val _state = MutableStateFlow<SearchState>(SearchState.Idle)
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private val nextId = AtomicLong(System.currentTimeMillis())
    private val waiting = AtomicInteger(0)
    @Volatile private var lastQuery = ""

    fun submit(textBuffer: String) {
        val query = textBuffer.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH)
        if (query.isEmpty()) { _state.value = SearchState.Failed("", SearchFailure.EMPTY); return }
        if (waiting.get() > 0 && query == lastQuery) return // a double tap must not queue the same search twice
        lastQuery = query
        val id = nextId.incrementAndGet()
        val ahead = waiting.getAndIncrement()
        _state.value = if (ahead == 0) SearchState.Sending(query, 1, ATTEMPTS) else SearchState.Queued(query, ahead)
        queue.submit {
            try { run(query, id) } finally { waiting.decrementAndGet() }
        }
    }

    fun retry() { if (lastQuery.isNotEmpty()) submit(lastQuery) }

    fun clear() { if (waiting.get() == 0) _state.value = SearchState.Idle }

    private suspend fun run(query: String, id: Long) {
        var attempt = 1
        while (true) {
            _state.value = SearchState.Sending(query, attempt, ATTEMPTS)
            val reply = link.get("yt?q=${URLEncoder.encode(query, "UTF-8")}&id=$id")
            // Only a lost or timed-out request is repeated. The TV ignores a repeated id, so a retry can never open a second search.
            if (reply != TvReply.Unreachable || attempt == ATTEMPTS) {
                _state.value = SearchReplyMapper.map(query, reply)
                return
            }
            delay(BACKOFF_MS shl (attempt - 1))
            attempt++
        }
    }

    private companion object {
        const val ATTEMPTS = 3
        const val BACKOFF_MS = 400L
        const val MAX_LENGTH = 200
    }
}

private fun SearchState.describe(): String? = when (this) {
    SearchState.Idle -> null
    is SearchState.Queued -> "Waiting for $ahead earlier search(es)..."
    is SearchState.Sending ->
        if (attempt == 1) "Sending \u201C$query\u201D to the TV..." else "The TV did not answer. Retrying ($attempt/$maxAttempts)..."
    is SearchState.Opened -> "Opened YouTube search for \u201C$query\u201D on the TV."
    is SearchState.Failed -> when (reason) {
        SearchFailure.EMPTY -> "Type something to search for."
        SearchFailure.UNREACHABLE -> "Can't reach the TV. Check Wi-Fi and the IP address, then tap Retry."
        SearchFailure.WRONG_PIN -> "The TV rejected the PIN. Tap Change TV and enter the PIN shown on the TV."
        SearchFailure.NO_YOUTUBE -> "YouTube isn't installed on the TV."
        SearchFailure.NOT_HANDLED -> "The TV's YouTube app did not accept the search link."
        SearchFailure.NO_OVERLAY -> "On the TV, allow 'Display over other apps' for TV Dash (Phone remote card), then tap Retry."
        SearchFailure.UNKNOWN -> "The TV sent an unexpected answer. Update TV Dash on the TV, then tap Retry."
    }
}

/** The live status line under the text box: progress, success, and plain-language errors with a Retry button. */
@Composable
fun SearchStatusLine(state: SearchState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val message = state.describe()
    if (message != null) {
        val failed = state as? SearchState.Failed
        Row(
            modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = if (failed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (failed?.reason?.retryable == true) TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}
