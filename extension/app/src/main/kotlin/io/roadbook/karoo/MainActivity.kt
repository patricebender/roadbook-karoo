package io.roadbook.karoo

import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.hammerhead.karooext.KarooSystemService
import io.roadbook.karoo.build.BuildController
import io.roadbook.karoo.build.BuildState
import io.roadbook.karoo.data.Category
import io.roadbook.karoo.data.ConfigStore
import io.roadbook.karoo.data.PoiDatabase
import io.roadbook.karoo.data.PoiQuery
import io.roadbook.karoo.data.RoadbookConfig
import io.roadbook.karoo.data.RoadbookRepository
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var configStore: ConfigStore
    private lateinit var repository: RoadbookRepository
    private lateinit var query: PoiQuery

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = ConfigStore(applicationContext)
        repository = RoadbookRepository.get(applicationContext)
        query = PoiQuery(PoiDatabase.get(applicationContext))

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ConfigScreen(
                        configStore = configStore,
                        repository = repository,
                        onDetourChange = { m -> lifecycleScope.launch { configStore.setDetour(m) } },
                        onCategoryToggle = { c, on ->
                            lifecycleScope.launch { configStore.setCategoryEnabled(c, on) }
                        },
                        onBuild = ::runBuild,
                    )
                }
            }
        }
    }

    /** Build from the app by spinning up a short-lived Karoo connection. */
    private fun runBuild() {
        repository.setBuildState(BuildState.Building("Connecting…"))
        val system = KarooSystemService(applicationContext)
        system.connect { connected ->
            if (!connected) {
                repository.setBuildState(BuildState.Error("Karoo not connected"))
                return@connect
            }
            lifecycleScope.launch {
                BuildController(system, configStore, repository, query).runBuild()
                system.disconnect()
            }
        }
    }
}

@Composable
private fun ConfigScreen(
    configStore: ConfigStore,
    repository: RoadbookRepository,
    onDetourChange: (Int) -> Unit,
    onCategoryToggle: (Category, Boolean) -> Unit,
    onBuild: () -> Unit,
) {
    val config by configStore.config.collectAsStateWithLifecycle(initialValue = RoadbookConfig())
    val buildState by repository.buildState.collectAsStateWithLifecycle()
    val building = buildState is BuildState.Building

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Roadbook", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        val radiusLabel = if (config.detourMeters >= 1000) {
            "%.1f km".format(config.detourMeters / 1000.0)
        } else {
            "${config.detourMeters} m"
        }
        Text("Detour radius: $radiusLabel")
        Slider(
            value = config.detourMeters.toFloat(),
            onValueChange = { raw ->
                // Snap to 500 m steps.
                val step = RoadbookConfig.DETOUR_STEP_METERS
                onDetourChange((raw / step).roundToInt() * step)
            },
            valueRange = RoadbookConfig.MIN_DETOUR_METERS.toFloat()..RoadbookConfig.MAX_DETOUR_METERS.toFloat(),
            // Discrete stops between min and max at 500 m each.
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

        Button(
            onClick = onBuild,
            enabled = !building,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (buildState is BuildState.Success || buildState is BuildState.Error) "Rebuild" else "Build now")
        }

        Spacer(Modifier.height(16.dp))
        BuildStatus(buildState)
    }
}

@Composable
private fun BuildStatus(state: BuildState) {
    when (state) {
        is BuildState.Idle -> Unit

        is BuildState.Building -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("  ${state.phase}", style = MaterialTheme.typography.bodyMedium)
        }

        is BuildState.Success -> Column {
            val ago = DateUtils.getRelativeTimeSpanString(
                state.atEpochMs,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            )
            Text(
                "${state.count} POIs · $ago",
                style = MaterialTheme.typography.titleSmall,
            )
            // Per-category breakdown, in enum order for stable layout.
            val parts = Category.entries
                .mapNotNull { c -> state.byCategory[c]?.let { "${c.label}: $it" } }
            if (parts.isNotEmpty()) {
                Text(
                    parts.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        is BuildState.Error -> Text(
            state.message,
            color = Color(0xFFB00020),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
