package io.roadbook.karoo.ui.field

import androidx.compose.ui.graphics.Color
import io.roadbook.karoo.data.Category

/**
 * Glance-side visual language for a category, mirroring [io.roadbook.karoo.ui.CategoryStyle]
 * (same palette as the map pins and route strip). Glance renders as RemoteViews and
 * can't use the app's Material `ImageVector` icons, so each category carries an emoji
 * glyph that draws as plain text — no drawable wiring needed.
 */
data class FieldStyle(val color: Color, val glyph: String, val label: String)

// Category tint — kept identical to ui/CategoryStyle.kt so the field reads as a set
// with the pins and strip.
private val RESTAURANT = Color(0xFFD84315)
private val SUPERMARKET = Color(0xFF1565C0)
private val CAFE_BAR = Color(0xFFE8820C)
private val WATER = Color(0xFF0097A7)
private val TOILET = Color(0xFF6A1B9A)
private val BIKE = Color(0xFF2E7D32)
private val FUEL = Color(0xFF455A64)
private val ICE_CREAM = Color(0xFFE91E63)
private val HOTEL = Color(0xFF5E35B1)

private val STYLES: Map<Category, FieldStyle> = mapOf(
    Category.RESTAURANTS to FieldStyle(RESTAURANT, "🍴", "Food"),
    Category.SUPERMARKETS to FieldStyle(SUPERMARKET, "🛒", "Shop"),
    Category.CAFE_BAR to FieldStyle(CAFE_BAR, "☕", "Café"),
    Category.WATER to FieldStyle(WATER, "💧", "Water"),
    Category.TOILET to FieldStyle(TOILET, "🚻", "Toilet"),
    Category.BIKE to FieldStyle(BIKE, "🚲", "Bike"),
    Category.FUEL to FieldStyle(FUEL, "⛽", "Fuel"),
    Category.ICE_CREAM to FieldStyle(ICE_CREAM, "🍦", "Ice"),
    Category.HOTELS to FieldStyle(HOTEL, "🛏️", "Hotel"),
)

fun styleFor(category: Category): FieldStyle =
    STYLES[category] ?: FieldStyle(Color(0xFF757575), "📍", category.label)
