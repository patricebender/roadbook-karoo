package io.roadbook.karoo.data

/**
 * POI categories the rider can toggle. `id` matches the backend contract
 * (backend/src/contract.ts Category) and is what we send in the build request.
 */
enum class Category(val id: String, val label: String) {
    FOOD_DRINK("food_drink", "Food & drink"),
    WATER_RESTROOM("water_restroom", "Water & restrooms"),
    BIKE("bike", "Bike shops"),
    FUEL("fuel", "Fuel stations"),
}

/** Rider-configurable build settings. */
data class RoadbookConfig(
    /** Detour search radius around the route, in meters. */
    val detourMeters: Int = DEFAULT_DETOUR_METERS,
    val enabledCategories: Set<Category> = Category.entries.toSet(),
) {
    companion object {
        const val DEFAULT_DETOUR_METERS = 500
        const val MIN_DETOUR_METERS = 100
        const val MAX_DETOUR_METERS = 2000
    }
}
