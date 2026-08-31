package io.roadbook.karoo.data

import android.content.Context
import io.requery.android.database.sqlite.SQLiteDatabase
import timber.log.Timber
import java.io.File

/**
 * The on-device POI database. Uses requery's bundled SQLite (guarantees the
 * R*Tree module, which Android's built-in SQLite may omit).
 *
 * On first run the app DB is seeded from the bundled POI asset (Germany, gzipped and
 * R*Tree-stripped by the data pipeline). It re-seeds whenever [BUNDLED_DB_VERSION]
 * outranks the installed `PRAGMA user_version`, so a schema/tags change ships a fresh copy.
 *
 * Installs are **additive**: the rider downloads regions (a country, a Bundesland, …) and
 * each is *merged into* the live DB via [installFromFile] rather than replacing it, so
 * coverage accumulates. Every `poi` row carries a `region_id` (which download inserted it),
 * so overlapping regions dedup by `osm_id` and a region can be removed again
 * ([removeRegion]).
 */
class PoiDatabase private constructor(private val dbFile: File) {

    private val db: SQLiteDatabase by lazy {
        SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null).also(::ensureSchema)
    }

    fun writableDatabase(): SQLiteDatabase = db

    private fun close() {
        runCatching { db.close() }
    }

    fun poiCount(): Int =
        db.rawQuery("SELECT COUNT(*) FROM poi", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    companion object {
        private const val DB_NAME = "pois.sqlite"
        // The bundled seed. Stored uncompressed: AGP's asset merger auto-inflates any
        // *.gz asset and strips the suffix, so a gzipped seed would (a) end up under a
        // different name than we open here and (b) save nothing — the APK zip DEFLATEs
        // the entry regardless. Ship the raw .sqlite and let the APK compress it.
        private const val SEED_ASSET = "pois-germany.sqlite"

        /** The region id the bundled seed installs as (see [Region.SEED_REGION_ID]). */
        private const val SEED_REGION_ID = Region.SEED_REGION_ID

        // Version of the bundled asset. Bump in lockstep with `user_version` set by
        // the data pipeline whenever the schema/tags change, so existing installs
        // re-seed instead of running against a stale DB missing the new columns.
        //
        // Also the schema contract for downloaded region files: the manifest's
        // schemaVersion and each file's `PRAGMA user_version` must equal this, else the
        // file was built for a different app version and [installFromFile] rejects it.
        const val BUNDLED_DB_VERSION = 7

        @Volatile
        private var instance: PoiDatabase? = null

        fun get(context: Context): PoiDatabase =
            instance ?: synchronized(this) {
                instance ?: create(context).also { instance = it }
            }

        private fun ensureSchema(d: SQLiteDatabase) {
            d.execSQL(
                """
                CREATE TABLE IF NOT EXISTS poi (
                  id        INTEGER PRIMARY KEY,
                  osm_id    TEXT NOT NULL,
                  lat       REAL NOT NULL,
                  lng       REAL NOT NULL,
                  type      TEXT NOT NULL,
                  category  TEXT NOT NULL,
                  name      TEXT,
                  tags      TEXT,
                  region_id TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent(),
            )
            d.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS poi_rtree USING rtree(id, minLat, maxLat, minLng, maxLng)",
            )
            d.execSQL("CREATE INDEX IF NOT EXISTS idx_poi_category ON poi(category)")
            d.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_poi_osm ON poi(osm_id)")
        }

        /**
         * Merge a downloaded region file into the live POI database (additive).
         *
         * [gunzippedDb] is a decompressed region file: a `poi` table only (rows stamped
         * with their `region_id`), `user_version = BUNDLED_DB_VERSION`, R*Tree/indexes
         * stripped by the pipeline to shrink the download. This validates it, then appends
         * its rows to the live DB — `INSERT OR IGNORE` on the unique `osm_id` index, so a
         * region overlapping data already installed is a no-op for the shared POIs (first
         * region to bring an `osm_id` wins). The R*Tree is rebuilt for the merged result.
         *
         * Returns the number of rows actually inserted (the coverage delta) on success, or
         * null if the file is invalid (wrong schema version, failed integrity check, or no
         * rows) — in which case the live DB is left untouched. [gunzippedDb] is consumed.
         */
        fun installFromFile(context: Context, gunzippedDb: File, regionId: String): Int? {
            val inserted = runCatching { mergeInto(context, gunzippedDb) }
                .onFailure { Timber.e(it, "region $regionId failed validation/merge") }
                .getOrNull()
            gunzippedDb.delete()
            if (inserted == null) return null
            Timber.d("installed region $regionId: +$inserted POIs")
            return inserted
        }

        /**
         * Seed/merge helper shared by [installFromFile] and [seedFromAsset]. Validates the
         * region file, attaches it to the live DB, appends rows with `INSERT OR IGNORE`,
         * and rebuilds the R*Tree. Throws if the file is unusable (caller cleans up).
         * Returns the rows inserted.
         */
        private fun mergeInto(context: Context, regionFile: File): Int {
            validateRegionFile(regionFile)
            val appCtx = context.applicationContext
            synchronized(this) {
                val live = get(appCtx).writableDatabase()
                val before = countRows(live)
                live.execSQL("ATTACH DATABASE ? AS src", arrayOf<Any?>(regionFile.absolutePath))
                try {
                    live.beginTransaction()
                    try {
                        live.execSQL(
                            "INSERT OR IGNORE INTO poi " +
                                "(osm_id, lat, lng, type, category, name, tags, region_id) " +
                                "SELECT osm_id, lat, lng, type, category, name, tags, region_id " +
                                "FROM src.poi",
                        )
                        // Rebuild the R*Tree wholesale from poi. Cheap enough (~few s even
                        // for ~300k rows) and always correct vs. tracking new ids.
                        live.execSQL("DELETE FROM poi_rtree")
                        live.execSQL(
                            "INSERT INTO poi_rtree (id, minLat, maxLat, minLng, maxLng) " +
                                "SELECT id, lat, lat, lng, lng FROM poi",
                        )
                        live.setTransactionSuccessful()
                    } finally {
                        live.endTransaction()
                    }
                } finally {
                    live.execSQL("DETACH DATABASE src")
                }
                return countRows(live) - before
            }
        }

        /** Validate a region file's schema version, integrity and non-emptiness. */
        private fun validateRegionFile(file: File) {
            val d = SQLiteDatabase.openDatabase(
                file.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
            )
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
                val count = countRows(d)
                require(count > 0) { "region file has no POIs" }
            } finally {
                d.close()
            }
        }

        /**
         * Remove a previously-installed region: drop its rows by `region_id` and rebuild
         * the R*Tree. Rows an overlapping region contributed under a *different* id stay;
         * a shared POI whose `osm_id` was first inserted by [id] is dropped and refilled by
         * re-downloading the neighbour (acceptable for a rebuildable cache). Returns the
         * row count removed.
         */
        fun removeRegion(context: Context, id: String): Int {
            val appCtx = context.applicationContext
            synchronized(this) {
                val live = get(appCtx).writableDatabase()
                val before = countRows(live)
                live.beginTransaction()
                try {
                    live.execSQL("DELETE FROM poi WHERE region_id = ?", arrayOf<Any?>(id))
                    live.execSQL("DELETE FROM poi_rtree")
                    live.execSQL(
                        "INSERT INTO poi_rtree (id, minLat, maxLat, minLng, maxLng) " +
                            "SELECT id, lat, lat, lng, lng FROM poi",
                    )
                    live.setTransactionSuccessful()
                } finally {
                    live.endTransaction()
                }
                val removed = before - countRows(live)
                Timber.d("removed region $id: -$removed POIs")
                return removed
            }
        }

        private fun countRows(d: SQLiteDatabase): Int =
            d.rawQuery("SELECT COUNT(*) FROM poi", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }

        private fun create(context: Context): PoiDatabase {
            val appCtx = context.applicationContext
            val dbFile = File(appCtx.filesDir, DB_NAME)
            if (!dbFile.exists() || installedVersion(dbFile) < BUNDLED_DB_VERSION) {
                // Absent, or older than the bundled asset → (re)seed from scratch. A stale
                // copy is a read-only, rebuildable cache — safe to drop and reseed.
                dbFile.delete()
                val poi = PoiDatabase(dbFile)
                instance = poi
                seedFromAsset(appCtx, poi.writableDatabase())
                return poi
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

        /**
         * Seed the (freshly created, empty) live DB from the bundled Germany asset. The
         * asset is an R*Tree-stripped region file (rows stamped `region_id = germany`); it's
         * copied to a temp file and merged through the same path as a download. Stamps the
         * DB version so re-seeds are gated by [installedVersion].
         */
        private fun seedFromAsset(context: Context, live: SQLiteDatabase) {
            Timber.d("seeding POI DB from asset $SEED_ASSET")
            live.execSQL("PRAGMA user_version = $BUNDLED_DB_VERSION")
            val tmp = File(context.cacheDir, "seed-$SEED_REGION_ID.sqlite")
            runCatching {
                context.assets.open(SEED_ASSET).use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                }
                val inserted = mergeInto(context, tmp)
                Timber.d("seeded $inserted POIs ($SEED_REGION_ID)")
            }.onFailure {
                Timber.e(it, "failed to seed POI DB from asset")
                // Leave the empty schema in place; the rider can download regions.
            }
            tmp.delete()
        }
    }
}
