package io.roadbook.karoo.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.roadbook.karoo.build.BuildState
import io.roadbook.karoo.data.Category
import io.roadbook.karoo.data.RoadbookConfig
import kotlin.math.roundToInt

/**
 * Filter & build settings, reachable from the Waybook header filter icon. The Build
 * action lives in the top bar (always visible, no scroll needed) with the build status
 * right below it; detour radius and category toggles follow; Clear sits at the bottom.
 */
@Composable
fun FilterScreen(
    config: RoadbookConfig,
    buildState: BuildState,
    hasPins: Boolean,
    onDetourChange: (Int) -> Unit,
    onCategoryToggle: (Category, Boolean) -> Unit,
    onBuild: () -> Unit,
    onClear: () -> Unit,
    onOpenRegions: () -> Unit,
    onBack: () -> Unit,
) {
    val building = buildState is BuildState.Building

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar: back + title + the primary Build action, always on screen.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Filter", style = MaterialTheme.typography.titleLarge)
            }
            Button(onClick = onBuild, enabled = !building) {
                if (building) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        if (buildState is BuildState.Success || buildState is BuildState.Error) {
                            "Rebuild"
                        } else {
                            "Build"
                        },
                    )
                }
            }
        }

        // Build status, directly under the action that produces it.
        BuildStatus(
            buildState,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        HorizontalDivider()

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            val radiusLabel = formatDistance(config.detourMeters)
            Text("Detour radius: $radiusLabel")
            Slider(
                value = config.detourMeters.toFloat(),
                onValueChange = { raw ->
                    val step = RoadbookConfig.DETOUR_STEP_METERS
                    onDetourChange((raw / step).roundToInt() * step)
                },
                valueRange = RoadbookConfig.MIN_DETOUR_METERS.toFloat()..RoadbookConfig.MAX_DETOUR_METERS.toFloat(),
                steps = (RoadbookConfig.MAX_DETOUR_METERS - RoadbookConfig.MIN_DETOUR_METERS) /
                    RoadbookConfig.DETOUR_STEP_METERS - 1,
                enabled = !building,
            )
            Spacer(Modifier.height(16.dp))

            Text("Categories", style = MaterialTheme.typography.titleMedium)
            Category.entries.forEach { category ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(category.label)
                    Switch(
                        checked = category in config.enabledCategories,
                        onCheckedChange = { on -> onCategoryToggle(category, on) },
                        enabled = !building,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = onOpenRegions,
                enabled = !building,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Download regions")
            }

            if (hasPins) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onClear,
                    enabled = !building,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Clear all places")
                }
            }
        }
    }
}

@Composable
private fun BuildStatus(state: BuildState, modifier: Modifier = Modifier) {
    when (state) {
        is BuildState.Idle -> Unit

        is BuildState.Building -> Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text(state.phase, style = MaterialTheme.typography.bodyMedium)
        }

        is BuildState.Success -> Column(modifier = modifier) {
            val ago = DateUtils.getRelativeTimeSpanString(
                state.atEpochMs,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            )
            Text("${state.count} places · $ago", style = MaterialTheme.typography.titleSmall)
            val parts = Category.entries
                .mapNotNull { c -> state.byCategory[c]?.let { "${c.label}: $it" } }
            if (parts.isNotEmpty()) {
                Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall)
            }
        }

        is BuildState.Error -> Text(
            state.message,
            color = ClosedRed,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier,
        )
    }
}
