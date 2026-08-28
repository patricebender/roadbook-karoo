package io.roadbook.karoo.data

import android.content.Context
import io.requery.android.database.sqlite.SQLiteDatabase
import timber.log.Timber
import java.io.File

/**
 * The on-device POI database. Uses requery's bundled SQLite (guarantees the
 * R*Tree module, which Android's built-in SQLite may omit).
 *
 * On first run the app DB is seeded by copying the bundled Baden-Württemberg
 * asset. Additional regions are merged in later via [RegionInstaller].
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
              name     TEXT
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

    companion object {
        private const val DB_NAME = "pois.sqlite"
        private const val SEED_ASSET = "pois-baden-wuerttemberg.sqlite"

        @Volatile
        private var instance: PoiDatabase? = null

        fun get(context: Context): PoiDatabase =
            instance ?: synchronized(this) {
                instance ?: create(context).also { instance = it }
            }

        private fun create(context: Context): PoiDatabase {
            val appCtx = context.applicationContext
            val dbFile = File(appCtx.filesDir, DB_NAME)
            if (!dbFile.exists()) {
                seedFromAsset(appCtx, dbFile)
            }
            return PoiDatabase(dbFile)
        }

        /** Copy the bundled BW database into app storage on first run. */
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
