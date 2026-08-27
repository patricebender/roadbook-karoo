package io.roadbook.karoo.network

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import io.roadbook.karoo.BuildConfig
import io.roadbook.karoo.data.BuildPageResponse
import io.roadbook.karoo.data.BuildStartResponse
import io.roadbook.karoo.data.Poi
import io.roadbook.karoo.data.RoadbookConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * Calls the roadbook backend through Karoo's HTTP bridge ([MakeHttpRequest] /
 * [OnHttpResponse]) so requests use the device's WiFi or SIM.
 *
 * The backend delivers builds in pages because the bridge caps a response at
 * 100K: [startBuild]/[startNearby] return a [BuildStartResponse] handle, then
 * [fetchPage] pulls each page.
 */
class BackendClient(private val system: KarooSystemService) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Start a route build; returns the paging handle. */
    suspend fun startBuild(polyline: String, config: RoadbookConfig): BuildStartResponse {
        val body = buildJsonObject {
            put("polyline", polyline)
            put("detourMeters", config.detourMeters)
            put("categories", categoriesJson(config))
        }
        return postJson("$BASE/build/start", body.toString(), START_TIMEOUT_MS)
    }

    /** Start a nearby build (fallback when no route is loaded). */
    suspend fun startNearby(lat: Double, lng: Double, config: RoadbookConfig): BuildStartResponse {
        val body = buildJsonObject {
            put("lat", lat)
            put("lng", lng)
            put("radiusMeters", config.detourMeters)
            put("categories", categoriesJson(config))
        }
        return postJson("$BASE/nearby/start", body.toString(), START_TIMEOUT_MS)
    }

    /** Fetch one page of a started build. */
    suspend fun fetchPage(buildId: String, page: Int): List<Poi> {
        val response = withTimeout(PAGE_TIMEOUT_MS) {
            request("GET", "$BASE/build/$buildId/page/$page", null)
        }
        if (response.statusCode !in 200..299) {
            throw BackendException("page $page: HTTP ${response.statusCode}")
        }
        val text = response.body?.toString(Charsets.UTF_8).orEmpty()
        return json.decodeFromString<BuildPageResponse>(text).pois
    }

    private fun categoriesJson(config: RoadbookConfig) =
        JsonArray(config.enabledCategories.map { JsonPrimitive(it.id) })

    private suspend inline fun <reified T> postJson(
        url: String,
        jsonBody: String,
        timeoutMs: Long,
    ): T {
        val response = withTimeout(timeoutMs) { request("POST", url, jsonBody) }
        if (response.statusCode !in 200..299) {
            throw BackendException("HTTP ${response.statusCode}: ${response.error ?: "no body"}")
        }
        val text = response.body?.toString(Charsets.UTF_8).orEmpty()
        return json.decodeFromString<T>(text)
    }

    /** Dispatch a single HTTP request and suspend until it completes. */
    private suspend fun request(
        method: String,
        url: String,
        jsonBody: String?,
    ): HttpResponseState.Complete = suspendCancellableCoroutine { cont ->
        Timber.d("HTTP $method $url (${jsonBody?.length ?: 0} bytes)")
        var consumerId: String? = null
        consumerId = system.addConsumer<OnHttpResponse>(
            OnHttpResponse.MakeHttpRequest(
                method = method,
                url = url,
                headers = if (jsonBody != null) mapOf("Content-Type" to "application/json") else emptyMap(),
                body = jsonBody?.toByteArray(Charsets.UTF_8),
                // Send immediately; don't let the Karoo queue it until "connected".
                waitForConnection = false,
            ),
        ) { event ->
            when (val state = event.state) {
                is HttpResponseState.Complete -> {
                    consumerId?.let { system.removeConsumer(it) }
                    if (cont.isActive) cont.resume(state)
                }
                else -> Unit // Queued / InProgress — keep waiting
            }
        }
        cont.invokeOnCancellation { consumerId?.let { system.removeConsumer(it) } }
    }

    private companion object {
        val BASE: String = BuildConfig.BACKEND_BASE_URL
        const val START_TIMEOUT_MS = 60_000L // includes Overpass compute
        const val PAGE_TIMEOUT_MS = 15_000L
    }
}

class BackendException(message: String) : Exception(message)
