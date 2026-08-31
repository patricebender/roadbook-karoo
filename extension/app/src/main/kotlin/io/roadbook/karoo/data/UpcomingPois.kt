package io.roadbook.karoo.data

/**
 * Pure selection + formatting for the "Upcoming POIs" data field. No Android
 * dependencies so it can be unit-tested on the JVM.
 *
 * The field answers, per enabled category: what are the next POIs ahead on the
 * route and how far. "Ahead on route" is the POI's distance-along-route minus the
 * rider's current progress; POIs already passed drop out.
 */

/** One upcoming POI, resolved against the rider's current route progress. */
data class UpcomingPoi(
    val name: String?,
    val category: Category,
    /** Distance still to ride along the route to reach it, in meters. */
    val aheadMeters: Double,
    /** Cross-track detour off the route line, in meters (0 when unknown). */
    val detourMeters: Int,
)

/**
 * Group the next [perCat] POIs per enabled category, ordered by how far ahead they
 * are on the route. A POI can touch the route more than once
 * ([Poi.distancesAlongRoute]); we take its nearest crossing that is still ahead.
 *
 * @param progressMeters how far the rider has ridden along the route.
 * @param toleranceMeters how far behind [progressMeters] a POI may still count as
 *   "ahead" — absorbs GPS jitter so a POI right at the rider doesn't flicker away.
 */
fun upcomingByCategory(
    pois: List<Poi>,
    enabledCategories: Set<Category>,
    progressMeters: Double,
    perCat: Int = 3,
    toleranceMeters: Double = DEFAULT_TOLERANCE_METERS,
): Map<Category, List<UpcomingPoi>> {
    if (enabledCategories.isEmpty()) return emptyMap()

    val byCat = LinkedHashMap<Category, MutableList<UpcomingPoi>>()
    for (cat in enabledCategories) byCat[cat] = mutableListOf()

    for (poi in pois) {
        val cat = Category.ofType(poi.type) ?: continue
        val bucket = byCat[cat] ?: continue
        // Nearest crossing still ahead of the rider (within tolerance).
        val ahead = poi.distancesAlongRoute
            .map { it - progressMeters }
            .filter { it >= -toleranceMeters }
            .minOrNull() ?: continue
        bucket.add(UpcomingPoi(poi.name, cat, ahead, poi.detourMeters))
    }

    return byCat.mapValues { (_, list) ->
        list.sortedBy { it.aheadMeters }.take(perCat)
    }
}

/**
 * Distance shown to the rider. Under 10 km keeps one decimal (`5.2km`); at or above,
 * rounds to a whole km (`12km`) — decimals are noise at that range on a small screen.
 */
fun formatKm(meters: Double): String {
    val km = meters.coerceAtLeast(0.0) / 1000.0
    return if (km < 10.0) "${(km * 10).toInt() / 10.0}km" else "${km.toInt()}km"
}

/** Detour suffix for the nearest POI in a row, e.g. `·+200m`. Empty when negligible. */
fun formatDetour(detourMeters: Int): String =
    if (detourMeters <= 0) "" else "·+${detourMeters}m"

/** Truncate a POI name so it can't push the distance columns out of alignment. */
fun elideName(name: String?, maxChars: Int): String? {
    if (name == null) return null
    return if (name.length <= maxChars) name else name.take(maxChars - 1).trimEnd() + "…"
}

/** Absorbs GPS jitter around the rider's position (meters). */
const val DEFAULT_TOLERANCE_METERS = 50.0
