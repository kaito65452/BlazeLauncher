package io.github.kaito65452.blazelauncher.rustruntime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R

data class RustEditionUiState(
    val engineStatus: String = "not installed",
    val supportedVersions: List<String> = emptyList(),
    val canDownloadAssets: Boolean = false,
    val canLaunch: Boolean = false
)

@Composable
fun RustEditionScreen(
    modifier: Modifier = Modifier,
    state: RustEditionUiState = RustEditionUiState()
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.rust_edition),
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    modifier = Modifier.testTag("engine_status_text"),
                    text = stringResource(R.string.rust_engine_status, state.engineStatus),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.rust_supported_versions),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (state.supportedVersions.isEmpty()) {
                    Text(
                        modifier = Modifier.testTag("empty_versions_text"),
                        text = stringResource(R.string.rust_no_supported_versions),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn {
                        items(state.supportedVersions) { version ->
                            Text(
                                text = version,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                modifier = Modifier
                    .weight(1f)
                    .testTag("download_assets_button"),
                onClick = {},
                enabled = state.canDownloadAssets
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = stringResource(R.string.rust_download_assets))
                    Text(
                        text = "(${stringResource(R.string.rust_coming_soon)})",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Button(
                modifier = Modifier
                    .weight(1f)
                    .testTag("launch_button"),
                onClick = {},
                enabled = state.canLaunch
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = stringResource(R.string.rust_launch))
                    Text(
                        text = "(${stringResource(R.string.rust_coming_soon)})",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}
