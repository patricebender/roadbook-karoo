package io.roadbook.karoo.data

import io.hammerhead.karooext.KarooSystemService
import io.roadbook.karoo.util.httpRequest
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Fetches the region manifest and downloads region files, routed through the Karoo HTTP
 * bridge (like [WikipediaClient]/[PlacesClient]) so it works over the paired phone.
 *
 * The bridge delivers each response as a **whole `ByteArray`** and exposes **no
 * mid-download progress** (see the SDK's `HttpResponseState`: `Complete` carries the
 * full body; `InProgress`/`Queued` are empty). So a large file is downloaded with HTTP
 * **Range requests** — one chunk per request. That both bounds peak memory (a ~60 MB
 * Germany file never lands in one array) and *is* the progress signal: "chunk N of M".
 * GitHub release assets serve `Accept-Ranges: bytes`, so ranged GETs work on our host.
 *
 * The downloaded `.gz` is verified against the manifest sha256, then gunzipped to a temp
 * SQLite handed to [PoiDatabase.installFromFile].
 */
class RegionCatalogClient(private val system: KarooSystemService) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Progress across a download: [done]/[total] bytes of the compressed file. */
    data class Progress(val done: Long, val total: Long) {
        val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    sealed interface Result {
        /** Region merged into the DB; [poiCount] is the newly-added rows (the coverage delta). */
        data class Installed(val poiCount: Int) : Result
        /** App is older/newer than the manifest — user must update the app. */
        data class SchemaMismatch(val manifestVersion: Int, val appVersion: Int) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Fetch and parse the manifest. Returns null on any network/parse failure (caller
     * shows an offline/error state). Does not enforce the schema version here — that's
     * checked at download time so a mismatch can still list what's available.
     */
    suspend fun fetchManifest(): RegionManifest? {
        val complete = system.httpRequest(method = "GET", url = MANIFEST_URL) ?: return null
        val body = complete.body
        if (complete.statusCode !in 200..299 || body == null) {
            Timber.d("manifest fetch failed: ${complete.statusCode} ${complete.error}")
            return null
        }
        return runCatching {
            json.decodeFromString<RegionManifest>(body.decodeToString())
        }.onFailure { Timber.e(it, "manifest parse failed") }.getOrNull()
    }

    /**
     * Download [entry] from [manifest], verify it, and merge it into the live DB. Installs
     * are additive — the region's rows are appended (dedup by osm_id), not swapped in.
     * [scratchDir] is a writable dir for the temp `.gz`/`.sqlite` (use `context.cacheDir`).
     * [onProgress] is invoked per chunk.
     */
    suspend fun downloadAndInstall(
        context: android.content.Context,
        manifest: RegionManifest,
        entry: RegionManifestEntry,
        scratchDir: File,
        onProgress: (Progress) -> Unit,
    ): Result {
        if (manifest.schemaVersion != PoiDatabase.BUNDLED_DB_VERSION) {
            return Result.SchemaMismatch(manifest.schemaVersion, PoiDatabase.BUNDLED_DB_VERSION)
        }

        val url = manifest.baseUrl.trimEnd('/') + "/" + entry.file
        val gz = File(scratchDir, entry.file)
        val cleanup = { gz.delete(); Unit }

        val downloaded = runCatching {
            downloadRanged(url, entry.bytesGz, gz, onProgress)
        }.onFailure { Timber.e(it, "download failed") }.getOrDefault(false)
        if (!downloaded) {
            cleanup()
            return Result.Failed("download failed")
        }

        // Integrity: the sha256 gate catches truncation, corruption, or a wrong file.
        val actualSha = sha256Of(gz)
        if (!actualSha.equals(entry.sha256, ignoreCase = true)) {
            Timber.e("sha256 mismatch for ${entry.file}: got $actualSha want ${entry.sha256}")
            cleanup()
            return Result.Failed("checksum mismatch")
        }

        val sqlite = File(scratchDir, entry.id + ".sqlite")
        val gunzipped = runCatching { gunzip(gz, sqlite) }
            .onFailure { Timber.e(it, "gunzip failed") }.getOrDefault(false)
        cleanup()
        if (!gunzipped) {
            sqlite.delete()
            return Result.Failed("decompression failed")
        }

        // installFromFile consumes `sqlite` (deletes it), merges its rows into the live DB
        // and rebuilds the R*Tree. Recording the installed regionId
        // (ConfigStore.addInstalledRegion) is the caller's job — it owns the store,
        // mirroring how the other clients are wired.
        val count = PoiDatabase.installFromFile(context, sqlite, entry.id)
            ?: return Result.Failed("region file rejected on install")
        return Result.Installed(count)
    }

    /**
     * Download [url] into [dest] using Range requests of [CHUNK_BYTES], reporting
     * progress per chunk. [expectedTotal] (from the manifest) bounds the loop; the
     * server's `Content-Range`/`Content-Length` is trusted for the actual end. Falls
     * back to a single full GET if the first response is 200 (server ignored Range).
     * Returns false on any non-2xx or a short read.
     */
    private suspend fun downloadRanged(
        url: String,
        expectedTotal: Long,
        dest: File,
        onProgress: (Progress) -> Unit,
    ): Boolean {
        val startAll = System.currentTimeMillis()
        var chunks = 0
        dest.outputStream().use { out ->
            var offset = 0L
            var total = expectedTotal
            while (offset < total) {
                val end = minOf(offset + CHUNK_BYTES - 1, total - 1)
                val t0 = System.currentTimeMillis()
                val complete = system.httpRequest(
                    method = "GET",
                    url = url,
                    headers = mapOf("Range" to "bytes=$offset-$end"),
                ) ?: return false
                val elapsed = System.currentTimeMillis() - t0
                val body = complete.body ?: return false
                chunks++
                Timber.d(
                    "range chunk #$chunks status=${complete.statusCode} " +
                        "req=bytes=$offset-$end got=${body.size}B in ${elapsed}ms",
                )

                when (complete.statusCode) {
                    206 -> {
                        // Partial content: refine total from Content-Range if present.
                        contentRangeTotal(complete.headers)?.let { total = it }
                        // A body shorter than the requested window (that isn't the final
                        // chunk) means the bridge truncated it — bail rather than loop or
                        // write a corrupt file. Empty body would also spin forever.
                        val requested = (end - offset + 1).toInt()
                        if (body.isEmpty() || (body.size < requested && offset + body.size < total)) {
                            Timber.e("short range chunk: got ${body.size}B, wanted $requested")
                            return false
                        }
                        out.write(body)
                        offset += body.size
                    }
                    200 -> {
                        // Server ignored Range and sent the whole file in one shot. Only
                        // trust this if it's actually the whole file; a capped/short 200 is
                        // a truncation, not a complete download.
                        if (body.size.toLong() < total) {
                            Timber.e("200 without Range: ${body.size}B < $total; bridge truncation")
                            return false
                        }
                        out.write(body)
                        offset = body.size.toLong()
                        total = offset
                    }
                    else -> {
                        Timber.d("range GET failed: ${complete.statusCode} ${complete.error}")
                        return false
                    }
                }
                onProgress(Progress(done = offset, total = total))
            }
        }
        Timber.d(
            "download done: ${dest.length()}B in $chunks chunk(s), " +
                "${System.currentTimeMillis() - startAll}ms total",
        )
        return dest.length() > 0
    }

    /** Parse the total length out of a `Content-Range: bytes 0-1023/12345` header. */
    private fun contentRangeTotal(headers: Map<String, String>): Long? {
        val v = headers.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value
        return v?.substringAfterLast('/')?.trim()?.toLongOrNull()
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun gunzip(src: File, dest: File): Boolean {
        GZIPInputStream(src.inputStream()).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return dest.length() > 0
    }

    companion object {
        /** Stable manifest URL (a pinned `regions-latest` release asset). */
        const val MANIFEST_URL =
            "https://github.com/patricebender/roadbook-karoo/releases/download/regions-latest/manifest.json"

        /**
         * Per-request Range chunk. The Karoo HTTP bridge caps responses at ~100 KB
         * (System Service enforced, even on WiFi); over that it drops the response with
         * `RESPONSE_TOO_LARGE` and the request hangs. Each bridged request also carries a
         * fixed ~2.4 s overhead (marshalling + a fresh CDN redirect per GET), independent
         * of payload — so download time is request-count-bound, not bandwidth-bound. Pick
         * the largest chunk that stays safely under the cap: 96 KB leaves ~4 KB headroom
         * for response headers. A ~50 MB region is then ~530 sequential GETs.
         */
        private const val CHUNK_BYTES = 96L * 1024
    }
}
