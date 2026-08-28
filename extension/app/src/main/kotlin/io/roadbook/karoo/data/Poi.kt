package io.roadbook.karoo.data

import kotlinx.serialization.Serializable

/**
 * A point of interest returned by the backend. Mirrors the backend contract
 * (backend/src/contract.ts Poi) so the JSON deserializes directly.
 */
@Serializable
data class Poi(
    val id: String,
    val lat: Double,
    val lng: Double,
    /** Matches karoo-ext Symbol.POI.Types (COFFEE, FOOD, …). */
    val type: String,
    val name: String? = null,
    val distancesAlongRoute: List<Double> = emptyList(),
    val tags: Map<String, String> = emptyMap(),
)
