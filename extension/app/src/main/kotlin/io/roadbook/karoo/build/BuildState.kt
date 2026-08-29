package io.roadbook.karoo.build

import io.roadbook.karoo.data.Category

/** Observable state of the roadbook build, driving the UI. */
sealed interface BuildState {
    data object Idle : BuildState

    /** In progress (querying the local database). */
    data class Building(val phase: String = "Searching…") : BuildState

    /** Completed: total plus a per-category breakdown for glanceable feedback. */
    data class Success(
        val count: Int,
        val byCategory: Map<Category, Int>,
        val atEpochMs: Long,
    ) : BuildState

    data class Error(val message: String) : BuildState
}
