package io.roadbook.karoo.data

import android.content.Context
import io.requery.android.database.sqlite.SQLiteDatabase
import timber.log.Timber
import java.io.File

/**
 * The on-device POI database. Uses requery's bundled SQLite (guarantees the
 * R*Tree module, which Android's built-in SQLite may omit).
 *
 * On first run the app DB is seeded by copying the bundled POI asset (Baden-Württemberg
 * + Hessen, merged by the data pipeline). It re-seeds whenever [BUNDLED_DB_VERSION]
 * outranks the installed `PRAGMA user_version`, so a schema/tags change ships a fresh copy.
 *
 * The rider can also replace the bundled seed with a downloaded region (Germany,
 * a Bundesland, or another country) via [installFromFile] — the region picker's payload.
 */
class PoiDatabase private constructor(private val dbFile: File) {

    private val db: SQLiteDatabase by lazy {
        SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null).also(::ensureSchema)
    }

    fun writableDatabase(): SQLiteDatabase = db

    private fun ensureSchema(d: SQLiteDatabase) {
        d.execSQL(
            """
            CREATE TABLE IF NOT EXISTS poi (
              id       INTEGER PRIMARY KEY,
              osm_id   TEXT NOT NULL,
              lat      REAL NOT NULL,
              lng      REAL NOT NULL,
              type     TEXT NOT NULL,
              category TEXT NOT NULL,
              name     TEXT,
              tags     TEXT
            )
            """.trimIndent(),
        )
        d.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS poi_rtree USING rtree(id, minLat, maxLat, minLng, maxLng)",
        )
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_poi_category ON poi(category)")
        d.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_poi_osm ON poi(osm_id)")
    }

    fun poiCount(): Int =
        db.rawQuery("SELECT COUNT(*) FROM poi", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    private fun close() {
        runCatching { db.close() }
    }

    companion object {
        private const val DB_NAME = "pois.sqlite"
        private const val SEED_ASSET = "pois-baden-wuerttemberg.sqlite"

        // Version of the bundled asset. Bump in lockstep with `user_version` set by
        // the data pipeline whenever the schema/tags change, so existing installs
        // re-seed instead of running against a stale DB missing the new columns.
        //
        // Also the schema contract for downloaded region files: the manifest's
        // schemaVersion and each file's `PRAGMA user_version` must equal this, else the
        // file was built for a different app version and [installFromFile] rejects it.
        const val BUNDLED_DB_VERSION = 5

        @Volatile
        private var instance: PoiDatabase? = null

        fun get(context: Context): PoiDatabase =
            instance ?: synchronized(this) {
                instance ?: create(context).also { instance = it }
            }

        /**
         * Replace the live POI database with a downloaded region file.
         *
         * [gunzippedDb] is a decompressed region file: a `poi` table only, stamped
         * `user_version = BUNDLED_DB_VERSION`, with the R*Tree and indexes stripped by
         * the pipeline to shrink the download. This validates it, rebuilds the derived
         * R*Tree + indexes in place, then atomically swaps it over [DB_NAME] and drops
         * the cached singleton so the next [get] reopens the new region.
         *
         * Returns the installed POI count on success, or null if the file is invalid
         * (wrong schema version, failed integrity check, or no rows) — in which case the
         * live DB is left untouched. [gunzippedDb] is consumed (moved or deleted).
         */
        fun installFromFile(context: Context, gunzippedDb: File, regionId: String): Int? {
            val appCtx = context.applicationContext
            val prepared = runCatching { prepareForInstall(gunzippedDb) }
                .onFailure { Timber.e(it, "region file failed validation/rebuild") }
                .getOrNull()
            if (prepared == null) {
                gunzippedDb.delete()
                return null
            }

            synchronized(this) {
                instance?.close()
                instance = null
                val dest = File(appCtx.filesDir, DB_NAME)
                // Atomic swap: rename the fully-prepared file over the live DB. On the
                // same filesystem (both in filesDir) this is atomic; fall back to copy.
                if (!gunzippedDb.renameTo(dest)) {
                    dest.delete()
                    gunzippedDb.copyTo(dest, overwrite = true)
                    gunzippedDb.delete()
                }
            }
            Timber.d("installed region $regionId: ${prepared.count} POIs")
            return prepared.count
        }

        private data class Prepared(val count: Int)

        /**
         * Validate a downloaded region file and rebuild its derived structures, in the
         * file itself (off the live DB), so the later swap is instant and atomic.
         * Throws if the file is unusable.
         */
        private fun prepareForInstall(file: File): Prepared {
            val d = SQLiteDatabase.openOrCreateDatabase(file.absolutePath, null)
            try {
                val version = d.rawQuery("PRAGMA user_version", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
                require(version == BUNDLED_DB_VERSION) {
                    "region file schema v$version != app v$BUNDLED_DB_VERSION"
                }
                val integrity = d.rawQuery("PRAGMA integrity_check", null).use { c ->
                    if (c.moveToFirst()) c.getString(0) else "unknown"
                }
                require(integrity == "ok") { "integrity_check: $integrity" }

                // Rebuild the R*Tree + indexes the pipeline stripped, then re-stamp the
                // version (schema DDL doesn't touch user_version, but be explicit).
                d.execSQL("DROP TABLE IF EXISTS poi_rtree")
                d.execSQL(
                    "CREATE VIRTUAL TABLE poi_rtree USING rtree(id, minLat, maxLat, minLng, maxLng)",
                )
                d.execSQL(
                    "INSERT INTO poi_rtree (id, minLat, maxLat, minLng, maxLng) " +
                        "SELECT id, lat, lat, lng, lng FROM poi",
                )
                d.execSQL("CREATE INDEX IF NOT EXISTS idx_poi_category ON poi(category)")
                d.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_poi_osm ON poi(osm_id)")

                val count = d.rawQuery("SELECT COUNT(*) FROM poi", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
                require(count > 0) { "region file has no POIs" }
                return Prepared(count)
            } finally {
                d.close()
            }
        }

        private fun create(context: Context): PoiDatabase {
            val appCtx = context.applicationContext
            val dbFile = File(appCtx.filesDir, DB_NAME)
            if (!dbFile.exists() || installedVersion(dbFile) < BUNDLED_DB_VERSION) {
                // Absent, or older than the bundled asset → (re)seed. A stale copy is
                // safe to overwrite: it's a read-only, rebuildable cache asset.
                seedFromAsset(appCtx, dbFile)
            }
            return PoiDatabase(dbFile)
        }

        /** Read `PRAGMA user_version` from an existing DB file; 0 if unreadable. */
        private fun installedVersion(dbFile: File): Int =
            runCatching {
                // requery's SQLiteDatabase isn't Closeable, so close explicitly.
                val d = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
                )
                try {
                    d.rawQuery("PRAGMA user_version", null).use { c ->
                        if (c.moveToFirst()) c.getInt(0) else 0
                    }
                } finally {
                    d.close()
                }
            }.getOrDefault(0)

        /** Copy the bundled POI database into app storage on first run. */
        private fun seedFromAsset(context: Context, dest: File) {
            Timber.d("seeding POI DB from asset $SEED_ASSET")
            runCatching {
                context.assets.open(SEED_ASSET).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                Timber.d("seeded ${dest.length() / 1024 / 1024}MB")
            }.onFailure {
                Timber.e(it, "failed to seed POI DB from asset")
                // Leave dbFile absent → an empty schema will be created; user can download.
                dest.delete()
            }
        }
    }
}
