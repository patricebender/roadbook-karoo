package io.roadbook.karoo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "roadbook_config")

/**
 * Reads/writes [RoadbookConfig] via DataStore. Both the app UI and the build
 * flow use this so config is a single source of truth.
 */
class ConfigStore(private val context: Context) {

    val config: Flow<RoadbookConfig> = context.dataStore.data.map { prefs ->
        val detour = prefs[DETOUR_KEY] ?: RoadbookConfig.DEFAULT_DETOUR_METERS
        val enabledIds = prefs[CATEGORIES_KEY]
        val categories = if (enabledIds == null) {
            Category.entries.toSet()
        } else {
            enabledIds.mapNotNull { id -> Category.entries.find { it.id == id } }.toSet()
        }
        RoadbookConfig(detourMeters = detour, enabledCategories = categories)
    }

    suspend fun setDetour(meters: Int) {
        context.dataStore.edit { it[DETOUR_KEY] = meters }
    }

    suspend fun setCategoryEnabled(category: Category, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[CATEGORIES_KEY]?.toMutableSet()
                ?: Category.entries.map { it.id }.toMutableSet()
            if (enabled) current.add(category.id) else current.remove(category.id)
            prefs[CATEGORIES_KEY] = current
        }
    }

    /**
     * The set of installed region ids. Installs are additive, so this grows as the rider
     * downloads regions and shrinks on removal. Empty means the DB is still the untouched
     * bundled seed (Germany); the picker treats empty as `{germany}` so the seed shows as
     * installed without a first-run write (see [Region.SEED_REGION_ID]).
     */
    val installedRegions: Flow<Set<String>> =
        context.dataStore.data.map { it[REGIONS_KEY] ?: emptySet() }

    suspend fun addInstalledRegion(regionId: String) {
        context.dataStore.edit { prefs ->
            prefs[REGIONS_KEY] = (prefs[REGIONS_KEY] ?: emptySet()) + regionId
        }
    }

    suspend fun removeInstalledRegion(regionId: String) {
        context.dataStore.edit { prefs ->
            prefs[REGIONS_KEY] = (prefs[REGIONS_KEY] ?: emptySet()) - regionId
        }
    }

    private companion object {
        val DETOUR_KEY = intPreferencesKey("detour_meters")
        val CATEGORIES_KEY = stringSetPreferencesKey("enabled_categories")
        val REGIONS_KEY = stringSetPreferencesKey("installed_regions")
    }
}
