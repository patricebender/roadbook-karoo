package io.roadbook.karoo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.roadbook.karoo.data.Region
import io.roadbook.karoo.data.RegionManifestEntry

/** Download UI state, owned by the host and passed down. */
sealed interface RegionDownloadState {
    data object Idle : RegionDownloadState
    /** Fetching the manifest before the list is actionable. */
    data object LoadingManifest : RegionDownloadState
    data object ManifestFailed : RegionDownloadState
    /** A download in flight for [regionId], [fraction] of the compressed bytes done. */
    data class Downloading(val regionId: String, val fraction: Float) : RegionDownloadState
    /** Rebuilding the R*Tree after download — indeterminate tail of the install. */
    data class Installing(val regionId: String) : RegionDownloadState
    data class Done(val regionId: String, val poiCount: Int) : RegionDownloadState
    data class Failed(val regionId: String, val reason: String) : RegionDownloadState
}

/**
 * Region picker. A grouped, scrollable list (Germany: Complete + 16 Bundesländer, then
 * Europe countries) from the bundled `regions.json`. Per-region download size comes from
 * the live [manifest]; tapping a region starts its download via [onDownload].
 *
 * Only one download runs at a time; while [state] is Downloading/Installing every row is
 * disabled and the active one shows progress.
 */
@Composable
fun RegionsScreen(
    regions: List<Region>,
    manifest: Map<String, RegionManifestEntry>,
    installedRegions: Set<String>,
    state: RegionDownloadState,
    onDownload: (Region) -> Unit,
    onRemove: (Region) -> Unit,
    onBack: () -> Unit,
) {
    val busy = state is RegionDownloadState.Downloading ||
        state is RegionDownloadState.Installing ||
        state is RegionDownloadState.LoadingManifest

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Regions", style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider()

        when (state) {
            is RegionDownloadState.ManifestFailed -> Text(
                "Couldn't reach the region list. Check your connection and try again.",
                color = ClosedRed,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
            is RegionDownloadState.LoadingManifest -> Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("Loading region list…", style = MaterialTheme.typography.bodyMedium)
            }
            else -> Unit
        }

        // Group order follows the catalog; sections keep the catalog's within-group order.
        val grouped = regions.groupBy { it.group }
        LazyColumn(modifier = Modifier.weight(1f)) {
            grouped.forEach { (group, groupRegions) ->
                item(key = "hdr-$group") {
                    Text(
                        group,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(groupRegions, key = { it.id }) { region ->
                    RegionRow(
                        region = region,
                        entry = manifest[region.id],
                        installed = Region.covers(installedRegions, region.id),
                        // Only a directly-installed region can be removed as a unit —
                        // Bundesländer covered only via "germany" aren't removable alone.
                        removable = region.id in installedRegions,
                        state = state,
                        enabled = !busy && manifest.containsKey(region.id),
                        onDownload = { onDownload(region) },
                        onRemove = { onRemove(region) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun RegionRow(
    region: Region,
    entry: RegionManifestEntry?,
    installed: Boolean,
    removable: Boolean,
    state: RegionDownloadState,
    enabled: Boolean,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    val downloadingThis = state is RegionDownloadState.Downloading && state.regionId == region.id
    val installingThis = state is RegionDownloadState.Installing && state.regionId == region.id
    val doneThis = state is RegionDownloadState.Done && state.regionId == region.id
    val failedThis = state is RegionDownloadState.Failed && state.regionId == region.id

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(region.label, style = MaterialTheme.typography.bodyLarge)
            val subtitle = when {
                installed -> "Installed"
                entry != null -> "${formatMb(entry.bytesGz)} · ${entry.poiCount} places"
                else -> "Unavailable"
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall)

            if (downloadingThis) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (state as RegionDownloadState.Downloading).fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (failedThis) {
                Text(
                    (state as RegionDownloadState.Failed).reason,
                    color = ClosedRed,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (doneThis) {
                Text(
                    "Installed ${(state as RegionDownloadState.Done).poiCount} places",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.size(8.dp))
        when {
            downloadingThis || installingThis ->
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✓", style = MaterialTheme.typography.titleMedium)
                if (removable) {
                    IconButton(onClick = onRemove, enabled = enabled) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove ${region.label}")
                    }
                }
            }
            else -> Button(onClick = onDownload, enabled = enabled) {
                Text(if (failedThis) "Retry" else "Get")
            }
        }
    }
}

/** Bytes → a compact "12.3 MB" / "820 KB" label for download sizes. */
private fun formatMb(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    return if (mb >= 1.0) "%.1f MB".format(mb) else "%d KB".format(bytes / 1024)
}
