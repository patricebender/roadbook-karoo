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

/** Response to POST /build/start — metadata to page through the result. */
@Serializable
data class BuildStartResponse(
    val buildId: String,
    val totalCount: Int,
    val pageSize: Int,
    val pageCount: Int,
)

/** One page of POIs from GET /build/:id/page/:n. */
@Serializable
data class BuildPageResponse(
    val page: Int,
    val pois: List<Poi> = emptyList(),
)
