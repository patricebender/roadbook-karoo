package io.roadbook.karoo.data

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import io.hammerhead.karooext.models.OnHttpResponse.MakeHttpRequest
import io.roadbook.karoo.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * On-demand opening-hours lookup via Google Places API (New), for POIs where OSM has
 * no `opening_hours` (common for fuel stations, some cafés/shops). Two steps:
 *   1. Text Search → resolve our name+coords to a Google Place ID (cacheable forever
 *      per Maps ToS; we persist it so repeat checks skip this call).
 *   2. Place Details → fetch `regularOpeningHours`.
 *
 * **Hours are never persisted** — Maps ToS forbids caching Places content beyond
 * Place IDs and coordinates. Callers hold the result in memory for the current view
 * only and display it live with "via Google" attribution.
 *
 * Routed through the Karoo HTTP bridge like [WikipediaClient], so it works over the
 * paired phone. Disabled (returns null) when [BuildConfig.PLACES_API_KEY] is empty.
 */
class PlacesClient(private val system: KarooSystemService) {

    private val json = Json { ignoreUnknownKeys = true }
    private val apiKey = BuildConfig.PLACES_API_KEY

    val isConfigured: Boolean get() = apiKey.isNotEmpty()

    /** Resolved place + its hours as the same normalized model OSM produces. */
    data class Result(
        val placeId: String,
        val hours: OpeningHours.Hours,
    )

    /**
     * Look up hours for a POI. [knownPlaceId] skips the Text Search when we've already
     * resolved and cached the Place ID. Returns null when unconfigured or on any failure.
     */
    suspend fun hoursFor(
        name: String?,
        lat: Double,
        lng: Double,
        knownPlaceId: String? = null,
    ): Result? {
        if (!isConfigured) return null
        val placeId = knownPlaceId ?: resolvePlaceId(name, lat, lng) ?: return null
        return details(placeId)
    }

    /** Text Search (New) biased to the POI location → best-match Place ID. */
    private suspend fun resolvePlaceId(name: String?, lat: Double, lng: Double): String? {
        val query = name?.trim().orEmpty().ifEmpty { return null }
        val body = buildString {
            append("{\"textQuery\":").append(jsonString(query)).append(",")
            append("\"maxResultCount\":1,")
            append("\"locationBias\":{\"circle\":{\"center\":{")
            append("\"latitude\":").append(lat).append(",\"longitude\":").append(lng)
            append("},\"radius\":500.0}}}")
        }
        val complete = post(
            url = "https://places.googleapis.com/v1/places:searchText",
            body = body,
            fieldMask = "places.id",
        ) ?: return null
        if (complete.statusCode !in 200..299 || complete.body == null) {
            Timber.d("places textSearch failed: ${complete.statusCode} ${complete.error}")
            return null
        }
        return runCatching {
            json.parseToJsonElement(String(complete.body!!, Charsets.UTF_8))
                .jsonObject["places"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("id")?.jsonPrimitive?.content
        }.getOrNull()
    }

    /** Place Details (New) → regularOpeningHours, parsed into our schedule shape. */
    private suspend fun details(placeId: String): Result? {
        val complete = get(
            url = "https://places.googleapis.com/v1/places/$placeId",
            fieldMask = "id,regularOpeningHours",
        ) ?: return null
        if (complete.statusCode !in 200..299 || complete.body == null) {
            Timber.d("places details failed: ${complete.statusCode} ${complete.error}")
            return null
        }
        return runCatching {
            val root = json.parseToJsonElement(String(complete.body!!, Charsets.UTF_8)).jsonObject
            val hoursObj = root["regularOpeningHours"]?.jsonObject ?: return@runCatching null
            // Normalize to the same model OSM produces so the UI treats them identically.
            Result(placeId, OpeningHours.Hours.fromSchedule(parsePeriods(hoursObj)))
        }.onFailure { Timber.w(it, "places details parse failed") }.getOrNull()
    }

    /**
     * Convert Google `periods` (day 0=Sunday, open/close with hour+minute) into our
     * schedule keyed 0=Monday..6=Sunday. Handles the common case; a period that spans
     * midnight is clipped to its start day (good enough for the table).
     */
    private fun parsePeriods(hours: kotlinx.serialization.json.JsonObject): Map<Int, List<OpeningHours.TimeRange>> {
        val out = HashMap<Int, MutableList<OpeningHours.TimeRange>>()
        val periods = hours["periods"]?.jsonArray ?: return emptyMap()
        for (p in periods) {
            val po = p.jsonObject
            val open = po["open"]?.jsonObject ?: continue
            val close = po["close"]?.jsonObject
            val gDay = open["day"]?.jsonPrimitive?.int ?: continue
            val startMin = (open["hour"]?.jsonPrimitive?.int ?: 0) * 60 +
                (open["minute"]?.jsonPrimitive?.int ?: 0)
            val endMin = if (close != null) {
                (close["hour"]?.jsonPrimitive?.int ?: 0) * 60 +
                    (close["minute"]?.jsonPrimitive?.int ?: 0)
            } else {
                24 * 60 // no close → treat as open to end of day (e.g. 24/7 fragments)
            }
            val day = googleDayToMonFirst(gDay)
            out.getOrPut(day) { mutableListOf() }.add(OpeningHours.TimeRange(startMin, endMin))
        }
        return out
    }

    /** Google 0=Sun..6=Sat → our 0=Mon..6=Sun. */
    private fun googleDayToMonFirst(gDay: Int): Int = (gDay + 6) % 7

    // ---- HTTP through the Karoo bridge ----

    private suspend fun post(url: String, body: String, fieldMask: String) =
        request("POST", url, body.toByteArray(Charsets.UTF_8), fieldMask)

    private suspend fun get(url: String, fieldMask: String) =
        request("GET", url, null, fieldMask)

    private suspend fun request(
        method: String,
        url: String,
        body: ByteArray?,
        fieldMask: String,
    ): HttpResponseState.Complete? =
        suspendCancellableCoroutine { cont ->
            var consumerId: String? = null
            consumerId = system.addConsumer(
                MakeHttpRequest(
                    method = method,
                    url = url,
                    headers = mapOf(
                        "X-Goog-Api-Key" to apiKey,
                        "X-Goog-FieldMask" to fieldMask,
                        "Content-Type" to "application/json",
                    ),
                    body = body,
                    waitForConnection = true,
                ),
            ) { event: OnHttpResponse ->
                when (val state = event.state) {
                    is HttpResponseState.Complete -> {
                        consumerId?.let { system.removeConsumer(it) }
                        if (cont.isActive) cont.resume(state)
                    }
                    else -> Unit
                }
            }
            cont.invokeOnCancellation { consumerId?.let { system.removeConsumer(it) } }
        }

    /** JSON-encode a string value (quotes + escapes) for embedding in a request body. */
    private fun jsonString(s: String): String =
        kotlinx.serialization.json.JsonPrimitive(s).toString()
}
