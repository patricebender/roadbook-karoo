package io.roadbook.karoo.data

/**
 * POI categories the rider can toggle. `id` matches the backend contract
 * (backend/src/contract.ts Category) and is what we send in the build request.
 */
enum class Category(val id: String, val label: String) {
    FOOD_DRINK("food_drink", "Food & drink"),
    WATER("water", "Water"),
    TOILET("toilet", "Toilets"),
    BIKE("bike", "Bike shops"),
    FUEL("fuel", "Fuel stations");

    companion object {
        /** Map a POI `type` (as stored in the DB) back to its category. */
        fun ofType(type: String): Category? = when (type) {
            "COFFEE", "FOOD", "BAR", "CONVENIENCE_STORE" -> FOOD_DRINK
            "REST_STOP" -> WATER
            "RESTROOM" -> TOILET
            "BIKE_SHOP" -> BIKE
            "GAS_STATION" -> FUEL
            else -> null
        }
    }
}

/** Rider-configurable build settings. */
data class RoadbookConfig(
    /** Detour search radius around the route, in meters. */
    val detourMeters: Int = DEFAULT_DETOUR_METERS,
    val enabledCategories: Set<Category> = Category.entries.toSet(),
) {
    companion object {
        const val DEFAULT_DETOUR_METERS = 500
        const val MIN_DETOUR_METERS = 500
        const val MAX_DETOUR_METERS = 5000
        const val DETOUR_STEP_METERS = 500
    }
}
