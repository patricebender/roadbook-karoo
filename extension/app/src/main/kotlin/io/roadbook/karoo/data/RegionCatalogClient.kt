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
        /** Region installed; [poiCount] rows now live. */
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
     * Download [entry] from [manifest], verify it, and install it. [scratchDir] is a
     * writable dir for the temp `.gz`/`.sqlite` (use `context.cacheDir`). [context] is
     * needed for the install swap. [onProgress] is invoked per chunk.
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

        // installFromFile consumes `sqlite` (moves or deletes it) and rebuilds the R*Tree.
        // Persisting the installed regionId (ConfigStore.setInstalledRegion) is the
        // caller's job — it owns the store, mirroring how the other clients are wired.
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
        dest.outputStream().use { out ->
            var offset = 0L
            var total = expectedTotal
            while (offset < total) {
                val end = minOf(offset + CHUNK_BYTES - 1, total - 1)
                val complete = system.httpRequest(
                    method = "GET",
                    url = url,
                    headers = mapOf("Range" to "bytes=$offset-$end"),
                ) ?: return false
                val body = complete.body ?: return false

                when (complete.statusCode) {
                    206 -> {
                        // Partial content: refine total from Content-Range if present.
                        contentRangeTotal(complete.headers)?.let { total = it }
                        out.write(body)
                        offset += body.size
                    }
                    200 -> {
                        // Server ignored Range and sent the whole file in one shot.
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

        /** Per-request Range chunk. Small enough to bound peak memory on the Karoo. */
        private const val CHUNK_BYTES = 2L * 1024 * 1024
    }
}
