package io.roadbook.karoo.ui

/**
 * Label for a water `water_subtype` tag (set by the POI pipeline). Kept Compose-free and
 * separate from [CategoryStyle] so it's JVM-unit-testable without dragging in the
 * icon/`ImageVector` initializers. Falls back to "Water" for an unknown value.
 */
fun waterSubtypeLabel(subtype: String): String = when (subtype) {
    "tap" -> "Water tap"
    "fountain" -> "Fountain"
    "spring" -> "Spring"
    "well" -> "Well"
    "graveyard" -> "Graveyard"
    else -> "Water"
}
