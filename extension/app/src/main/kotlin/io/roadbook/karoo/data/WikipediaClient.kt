package io.roadbook.karoo.data

import io.hammerhead.karooext.KarooSystemService
import io.roadbook.karoo.util.httpRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.net.URLEncoder

/**
 * Fetches a short place description from Wikipedia's REST summary endpoint, routed
 * **through the Karoo HTTP bridge** ([MakeHttpRequest] → [OnHttpResponse]) rather
 * than a direct socket. This matters for on-the-go use: the bridge uses the Karoo's
 * own connectivity — including the **paired-phone companion link** — so the blurb
 * loads when riding with the phone in a pocket and no WiFi. A direct HttpURLConnection
 * would only work on WiFi.
 *
 * Online-only by nature; callers cache the result (see [RoadbookRepository]) so a
 * description survives offline once fetched. Absence of any link just yields null.
 */
class WikipediaClient(private val system: KarooSystemService) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Resolve a short extract for an OSM `wikipedia` tag like `de:Heidelberger Schloss`.
     * Returns null if the tag is absent/malformed or the request fails.
     */
    suspend fun summaryFor(wikipediaTag: String?): String? {
        val (lang, title) = parseTag(wikipediaTag) ?: return null
        val encoded = URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
        val url = "https://$lang.wikipedia.org/api/rest_v1/page/summary/$encoded"

        // Queue until the Karoo has a link (WiFi or paired phone).
        val complete = system.httpRequest(method = "GET", url = url) ?: return null
        val body = complete.body
        if (complete.statusCode !in 200..299 || body == null) {
            Timber.d("wikipedia fetch failed: ${complete.statusCode} ${complete.error}")
            return null
        }
        return runCatching {
            val obj = json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject
            obj["extract"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** `de:Heidelberger Schloss` → ("de", "Heidelberger Schloss"); null if malformed. */
    private fun parseTag(tag: String?): Pair<String, String>? {
        val t = tag?.trim().orEmpty()
        val colon = t.indexOf(':')
        if (colon <= 0 || colon == t.length - 1) return null
        val lang = t.substring(0, colon)
        // Guard against unexpected values (only simple language codes).
        if (!lang.all { it.isLetter() } || lang.length > 5) return null
        return lang to t.substring(colon + 1)
    }
}
