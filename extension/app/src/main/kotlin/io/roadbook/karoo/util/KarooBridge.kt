package io.roadbook.karoo.util

import android.content.Context
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.OnHttpResponse
import io.hammerhead.karooext.models.OnHttpResponse.MakeHttpRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Shared helpers over [KarooSystemService] so the one-shot consumer and short-lived
 * connection patterns live in one place instead of being hand-rolled in every caller.
 */

/**
 * Subscribe for a single emission of Karoo event [T], then unsubscribe. Returns null if
 * cancelled before an event arrives. Used by the build flow to grab the current
 * navigation/location state once.
 */
suspend inline fun <reified T : KarooEvent> KarooSystemService.awaitOnce(): T? =
    suspendCancellableCoroutine { cont ->
        var consumerId: String? = null
        consumerId = addConsumer<T> { event ->
            consumerId?.let { removeConsumer(it) }
            if (cont.isActive) cont.resume(event)
        }
        cont.invokeOnCancellation { consumerId?.let { removeConsumer(it) } }
    }

/**
 * Make one HTTP request through the Karoo bridge ([MakeHttpRequest] → [OnHttpResponse]),
 * resolving on the terminal [HttpResponseState.Complete]. The bridge uses the Karoo's own
 * connectivity — including the paired-phone companion link — so it works on-the-go, not
 * just on WiFi. [waitForConnection] queues the request until a link is up.
 *
 * Returns null on [timeoutMs] with no terminal event. This isn't just belt-and-braces: the
 * bridge caps responses at ~100 KB and, when exceeded, logs `RESPONSE_TOO_LARGE` and
 * **never delivers a Complete** (there's no error state in [HttpResponseState]) — without a
 * timeout the caller hangs forever. [waitForConnection] can also legitimately block a while,
 * so the timeout is generous.
 */
suspend fun KarooSystemService.httpRequest(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    body: ByteArray? = null,
    waitForConnection: Boolean = true,
    timeoutMs: Long = 30_000,
): HttpResponseState.Complete? =
    withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            var consumerId: String? = null
            consumerId = addConsumer(
                MakeHttpRequest(
                    method = method,
                    url = url,
                    headers = headers,
                    body = body,
                    waitForConnection = waitForConnection,
                ),
            ) { event: OnHttpResponse ->
                when (val state = event.state) {
                    is HttpResponseState.Complete -> {
                        consumerId?.let { removeConsumer(it) }
                        if (cont.isActive) cont.resume(state)
                    }
                    else -> Unit // Queued / InProgress → keep waiting
                }
            }
            cont.invokeOnCancellation { consumerId?.let { removeConsumer(it) } }
        }
    }.also { if (it == null) Timber.w("httpRequest timed out after ${timeoutMs}ms: $url") }

/**
 * Run [block] against a freshly-connected [KarooSystemService], disconnecting when done
 * (even on failure). Returns null if the connection can't be established. For the app's
 * short-lived, on-demand fetches (description, Google hours) that spin up a connection
 * just for one call.
 */
suspend fun <T> withKarooConnection(
    context: Context,
    block: suspend (KarooSystemService) -> T,
): T? {
    val system = KarooSystemService(context.applicationContext)
    val connected = suspendCoroutine { cont -> system.connect { cont.resume(it) } }
    if (!connected) return null
    return try {
        block(system)
    } finally {
        system.disconnect()
    }
}
