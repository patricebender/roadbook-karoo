package io.roadbook.karoo.data

import io.roadbook.karoo.util.LatLng
import io.roadbook.karoo.util.METERS_PER_DEG_LAT
import io.roadbook.karoo.util.cumulativeDistances
import io.roadbook.karoo.util.distanceToRoute
import io.roadbook.karoo.util.haversine
import kotlinx.serialization.json.Json
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Spatial POI queries against the on-device SQLite (R*Tree). Kotlin port of the
 * backend's poiStore.ts — a route corridor is one bbox R*Tree scan + an exact
 * point-to-segment refine. Runs fully offline, in-process.
 */
class PoiQuery(private val database: PoiDatabase) {

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        // Adaptive density: the route is split into fixed-length segments; each
        // segment keeps at most CAP POIs (nearest to the route). Sparse segments
        // may reach beyond the base radius (up to EXTEND_FACTOR×) to surface
        // isolated rural POIs; dense segments stay at the base radius and get
        // thinned to the cap — so a city can't flood the map.
        const val SEGMENT_METERS = 2_000.0
        const val PER_SEGMENT_CAP = 12
        const val SPARSE_THRESHOLD = 6      // below this in a segment → allow reach
        const val EXTEND_FACTOR = 2.0       // sparse segments reach up to 2× radius
        const val EXTEND_CAP_METERS = 5_000 // but never beyond this absolute max
    }

    /** A candidate POI with its computed route geometry. */
    private class Candidate(
        val row: Row,
        val distanceToRoute: Double,
        val distanceAlong: Double,
    )

    /**
     * POIs along the route with adaptive density: rural completeness + a hard
     * ceiling in dense areas. See companion for the tunables.
     */
    fun queryCorridor(
        route: List<LatLng>,
        radiusMeters: Int,
        categories: Set<Category>,
    ): List<Poi> {
        if (route.size < 2 || categories.isEmpty()) return emptyList()

        // Query with the extended bbox so sparse segments can reach further.
        val maxRadius = minOf((radiusMeters * EXTEND_FACTOR).toInt(), EXTEND_CAP_METERS)

        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLng = Double.MAX_VALUE; var maxLng = -Double.MAX_VALUE
        for (p in route) {
            minLat = minOf(minLat, p.lat); maxLat = maxOf(maxLat, p.lat)
            minLng = minOf(minLng, p.lng); maxLng = maxOf(maxLng, p.lng)
        }
        val dLat = maxRadius / METERS_PER_DEG_LAT
        val midLat = (minLat + maxLat) / 2
        val dLng = maxRadius / (METERS_PER_DEG_LAT * cos(Math.toRadians(midLat)))

        val candidates = candidatesInBox(
            minLat - dLat, maxLat + dLat, minLng - dLng, maxLng + dLng, categories,
        )

        // Compute geometry and bucket candidates by along-route segment.
        val cumulative = cumulativeDistances(route)
        val bySegment = HashMap<Int, MutableList<Candidate>>()
        for (r in candidates) {
            val (d, along) = distanceToRoute(route, cumulative, LatLng(r.lat, r.lng))
            if (d > maxRadius) continue
            val seg = (along / SEGMENT_METERS).toInt()
            bySegment.getOrPut(seg) { ArrayList() }.add(Candidate(r, d, along))
        }

        val out = ArrayList<Poi>()
        for ((_, list) in bySegment) {
            // Within-radius POIs are always eligible.
            val withinBase = list.filter { it.distanceToRoute <= radiusMeters }
            val eligible = if (withinBase.size < SPARSE_THRESHOLD) {
                // Sparse: also admit the nearest few beyond base radius (rural reach).
                list.sortedBy { it.distanceToRoute }.take(SPARSE_THRESHOLD)
            } else {
                withinBase
            }
            // Dense cap: keep the nearest to the route line.
            val kept = if (eligible.size > PER_SEGMENT_CAP) {
                eligible.sortedBy { it.distanceToRoute }.take(PER_SEGMENT_CAP)
            } else {
                eligible
            }
            for (c in kept) {
                out.add(
                    c.row.toPoi(
                        distancesAlongRoute = listOf(c.distanceAlong.roundToInt().toDouble()),
                        detourMeters = c.distanceToRoute.roundToInt(),
                    ),
                )
            }
        }
        out.sortBy { it.distancesAlongRoute.firstOrNull() ?: 0.0 }
        return out
    }

    /** POIs within [radiusMeters] of a point (fallback when no route is loaded). */
    fun queryNearby(center: LatLng, radiusMeters: Int, categories: Set<Category>): List<Poi> {
        if (categories.isEmpty()) return emptyList()
        val dLat = radiusMeters / METERS_PER_DEG_LAT
        val dLng = radiusMeters / (METERS_PER_DEG_LAT * cos(Math.toRadians(center.lat)))
        return candidatesInBox(
            center.lat - dLat, center.lat + dLat, center.lng - dLng, center.lng + dLng, categories,
        )
            .filter { haversine(center, LatLng(it.lat, it.lng)) <= radiusMeters }
            .map { it.toPoi(emptyList(), detourMeters = 0) }
    }

    private data class Row(
        val osmId: String,
        val lat: Double,
        val lng: Double,
        val type: String,
        val name: String?,
        val tags: Map<String, String>,
    ) {
        fun toPoi(distancesAlongRoute: List<Double>, detourMeters: Int) = Poi(
            id = "osm:$osmId",
            lat = lat,
            lng = lng,
            type = type,
            name = name,
            distancesAlongRoute = distancesAlongRoute,
            detourMeters = detourMeters,
            tags = tags,
        )
    }

    /** R*Tree range-scan within a bbox, filtered by category. */
    private fun candidatesInBox(
        minLat: Double, maxLat: Double, minLng: Double, maxLng: Double,
        categories: Set<Category>,
    ): List<Row> {
        // R*Tree constraints need numeric literals; the bbox values are our own
        // computed doubles (no user input → no injection). Category values are
        // bound as parameters.
        val placeholders = categories.joinToString(",") { "?" }
        val sql = """
            SELECT p.osm_id, p.lat, p.lng, p.type, p.name, p.tags
            FROM poi_rtree r
            JOIN poi p ON p.id = r.id
            WHERE r.maxLat >= $minLat AND r.minLat <= $maxLat
              AND r.maxLng >= $minLng AND r.minLng <= $maxLng
              AND p.category IN ($placeholders)
        """.trimIndent()
        val args = categories.map { it.id }.toTypedArray()
        val rows = ArrayList<Row>()
        database.writableDatabase().rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    Row(
                        osmId = c.getString(0),
                        lat = c.getDouble(1),
                        lng = c.getDouble(2),
                        type = c.getString(3),
                        name = if (c.isNull(4)) null else c.getString(4),
                        tags = if (c.isNull(5)) emptyMap() else parseTags(c.getString(5)),
                    ),
                )
            }
        }
        return rows
    }

    /** Parse the stored `tags` JSON blob into a map; empty on any failure. */
    private fun parseTags(jsonText: String): Map<String, String> =
        runCatching {
            json.decodeFromString<Map<String, String>>(jsonText)
        }.getOrDefault(emptyMap())
}
