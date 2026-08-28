package io.roadbook.karoo.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wc
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import io.roadbook.karoo.data.Category

/**
 * The shared visual language for a POI — one color + icon + human label per amenity
 * `type`, used by the route strip dots, list rows, and detail header so they read as
 * a set. Keyed by `type` (not category) so a Bar and a Café look and read differently.
 * Colors echo the mockup (warm cafe, green outdoors, …) and stay distinct on the
 * Karoo's small display.
 */
data class CategoryStyle(val color: Color, val icon: ImageVector, val label: String)

// Category tint, shared by the types within it (dots read by category color).
private val RESTAURANT = Color(0xFFD84315)
private val SUPERMARKET = Color(0xFF1565C0)
private val CAFE_BAR = Color(0xFFE8820C)
private val WATER = Color(0xFF0097A7)
private val TOILET = Color(0xFF6A1B9A)
private val BIKE = Color(0xFF2E7D32)
private val FUEL = Color(0xFF455A64)

private val STYLES: Map<String, CategoryStyle> = mapOf(
    "FOOD" to CategoryStyle(RESTAURANT, Icons.Filled.Restaurant, "Restaurant"),
    "CONVENIENCE_STORE" to CategoryStyle(SUPERMARKET, Icons.Filled.ShoppingCart, "Supermarket"),
    "COFFEE" to CategoryStyle(CAFE_BAR, Icons.Filled.LocalCafe, "Café"),
    "BAR" to CategoryStyle(CAFE_BAR, Icons.Filled.LocalBar, "Bar"),
    "REST_STOP" to CategoryStyle(WATER, Icons.Filled.WaterDrop, "Water"),
    "RESTROOM" to CategoryStyle(TOILET, Icons.Filled.Wc, "Toilet"),
    "BIKE_SHOP" to CategoryStyle(BIKE, Icons.AutoMirrored.Filled.DirectionsBike, "Bike shop"),
    "GAS_STATION" to CategoryStyle(FUEL, Icons.Filled.LocalGasStation, "Gas station"),
)

private val FALLBACK = CategoryStyle(Color(0xFF757575), Icons.Filled.Place, "Place")

/** Style + label for a POI, resolved from its `type`. */
fun styleForType(type: String): CategoryStyle = STYLES[type] ?: FALLBACK
