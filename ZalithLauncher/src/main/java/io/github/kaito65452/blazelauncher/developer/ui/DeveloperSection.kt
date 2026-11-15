/*
 * Blaze Launcher (based on Zalith Launcher 2)
 * Copyright (C) 2026 Blaze Launcher contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package io.github.kaito65452.blazelauncher.developer.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.ui.components.CardTitleLayout
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.CardPosition
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SettingsCard
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SettingsCardColumn
import com.movtery.zalithlauncher.ui.screens.content.settings.layouts.SwitchSettingsCard
import io.github.kaito65452.blazelauncher.developer.DeveloperDiagnosticsStore
import io.github.kaito65452.blazelauncher.developer.DeveloperModeManager
import io.github.kaito65452.blazelauncher.rustruntime.EngineDataPreparer
import io.github.kaito65452.blazelauncher.rustruntime.PrepareUiState
import io.github.kaito65452.blazelauncher.rustruntime.RustActivity
import io.github.kaito65452.blazelauncher.rustruntime.RustSessionHandoff

private fun launchRustSpikeActivity(context: Context) {
    RustSessionHandoff.prepareSpikeLaunch(context)
    DeveloperDiagnosticsStore.recordRustActivityEvent("Launched RustActivity from Developer menu")
    context.startActivity(Intent(context, RustActivity::class.java))
}

private fun copyDiagnosticReport(context: Context, text: String) {
    DeveloperDiagnosticsStore.copyToClipboard(context, "Blaze Diagnostic Report", text)
    Toast.makeText(context, "Diagnostic report copied", Toast.LENGTH_SHORT).show()
}

private fun copyTextWithToast(context: Context, label: String, text: String, toastMessage: String) {
    DeveloperDiagnosticsStore.copyToClipboard(context, label, text)
    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
}

private fun formatPrepareSummary(context: Context, state: PrepareUiState): String {
    val base = when {
        state.running -> listOf(state.phase, state.detail)
            .filter { it.isNotBlank() }
            .joinToString(" — ")
            .ifBlank { context.getString(R.string.developer_prepare_summary) }
        state.redactedError != null -> listOf(
            context.getString(R.string.developer_prepare_failed),
            state.detail
        ).filter { it.isNotBlank() }.joinToString(": ")
        state.completedSummary != null -> state.completedSummary
        state.prepared -> context.getString(R.string.developer_prepare_ready)
        else -> context.getString(R.string.developer_prepare_summary)
    }
    return if (state.warnings.isNotEmpty() && !state.running) {
        "$base\nWarning: ${state.warnings.joinToString("; ")}"
    } else {
        base
    }
}

@Composable
fun DeveloperSettingsSection(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val visibility = DeveloperModeManager.visibility(AllSettings.developerMode.state)
    if (!visibility.showDeveloperSection) return

    val prepareState by EngineDataPreparer.uiState.collectAsState()
    LaunchedEffect(Unit) {
        EngineDataPreparer.refreshPreparedState(context)
    }

    var dialogReport by remember { mutableStateOf<String?>(null) }
    var prepareErrorDetails by remember { mutableStateOf<String?>(null) }

    SettingsCardColumn(modifier = modifier) {
        CardTitleLayout {
            Text(
                modifier = Modifier.padding(all = 16.dp),
                text = stringResource(R.string.developer_section_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        SwitchSettingsCard(
            modifier = Modifier.fillMaxWidth(),
            position = CardPosition.Top,
            unit = AllSettings.developerMode,
            title = stringResource(R.string.developer_mode_title),
            summary = stringResource(R.string.developer_mode_summary)
        )

        SettingsCard(
            modifier = Modifier.fillMaxWidth(),
            position = CardPosition.Middle,
            title = stringResource(R.string.developer_capability_report_title),
            summary = stringResource(R.string.developer_capability_report_summary),
            onClick = {
                dialogReport = DeveloperDiagnosticsStore.collectAndBuildReport(context)
            }
        )

        SettingsCard(
            modifier = Modifier.fillMaxWidth(),
            position = CardPosition.Middle,
            title = stringResource(R.string.developer_prepare_title),
            summary = formatPrepareSummary(context, prepareState),
            onClick = {
                if (!prepareState.running) {
                    EngineDataPreparer.startInBackground(context)
                }
            }
        )

        if (prepareState.running || prepareState.redactedError != null || prepareState.warnings.isNotEmpty()) {
            PrepareActionStrip(
                state = prepareState,
                onCancel = { EngineDataPreparer.cancel(context) },
                onShowDetails = { prepareErrorDetails = prepareState.redactedError }
            )
        }

        SettingsCard(
            modifier = Modifier.fillMaxWidth(),
            position = CardPosition.Middle,
            title = stringResource(R.string.developer_rust_spike_title),
            summary = stringResource(R.string.developer_rust_spike_summary),
            onClick = { launchRustSpikeActivity(context) }
        )

        val latestSummary = DeveloperDiagnosticsStore.lastReportOutput?.lineSequence()?.firstOrNull()
            ?: stringResource(R.string.developer_copy_report_summary)
        SettingsCard(
            modifier = Modifier.fillMaxWidth(),
            position = CardPosition.Bottom,
            title = stringResource(R.string.developer_copy_report),
            summary = latestSummary,
            onClick = {
                val report = DeveloperDiagnosticsStore.collectAndBuildReport(context)
                copyDiagnosticReport(context, report)
                dialogReport = report
            }
        )
    }

    dialogReport?.let { report ->
        DiagnosticReportDialog(
            title = stringResource(R.string.developer_capability_report_title),
            confirmLabel = stringResource(R.string.developer_copy_report),
            report = report,
            onCopy = { copyDiagnosticReport(context, report) },
            onDismiss = { dialogReport = null }
        )
    }

    prepareErrorDetails?.let { details ->
        DiagnosticReportDialog(
            title = stringResource(R.string.developer_prepare_failed),
            confirmLabel = stringResource(R.string.developer_prepare_copy_details),
            report = details,
            onCopy = {
                copyTextWithToast(context, "Blaze Prepare Error", details, "Prepare details copied")
            },
            onDismiss = { prepareErrorDetails = null }
        )
    }
}

@Composable
private fun PrepareActionStrip(
    state: PrepareUiState,
    onCancel: () -> Unit,
    onShowDetails: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (state.warnings.isNotEmpty()) {
            Text(
                text = "Warning: ${state.warnings.joinToString("\n")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (state.running) {
            val frac = state.progressFraction
            if (frac != null) {
                LinearProgressIndicator(
                    progress = { frac.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (state.peakRssKb > 0) "VmHWM: ${state.peakRssKb / 1024} MB" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onCancel) {
                    Text(text = stringResource(R.string.developer_prepare_cancel))
                }
            }
        } else if (state.redactedError != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onShowDetails) {
                    Text(text = stringResource(R.string.developer_prepare_copy_details))
                }
            }
        }
    }
}

@Composable
fun DeveloperDiagnosticsButtons(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prepareState by EngineDataPreparer.uiState.collectAsState()
    LaunchedEffect(Unit) {
        EngineDataPreparer.refreshPreparedState(context)
    }

    var dialogReport by remember { mutableStateOf<String?>(null) }
    var prepareErrorDetails by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                modifier = Modifier
                    .weight(1f)
                    .testTag("developer_capability_report_button"),
                onClick = {
                    dialogReport = DeveloperDiagnosticsStore.collectAndBuildReport(context)
                }
            ) {
                Text(text = stringResource(R.string.developer_capability_report_title))
            }

            Button(
                modifier = Modifier
                    .weight(1f)
                    .testTag("developer_rust_spike_button"),
                onClick = { launchRustSpikeActivity(context) }
            ) {
                Text(text = stringResource(R.string.developer_rust_spike_title))
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
                    .testTag("developer_prepare_engine_data_button"),
                enabled = !prepareState.running,
                onClick = { EngineDataPreparer.startInBackground(context) }
            ) {
                Text(text = stringResource(R.string.developer_prepare_title))
            }

            OutlinedButton(
                modifier = Modifier
                    .weight(1f)
                    .testTag("developer_copy_report_button"),
                onClick = {
                    val report = DeveloperDiagnosticsStore.collectAndBuildReport(context)
                    copyDiagnosticReport(context, report)
                    dialogReport = report
                }
            ) {
                Text(text = stringResource(R.string.developer_copy_report))
            }
        }

        if (prepareState.running || prepareState.redactedError != null || prepareState.completedSummary != null || prepareState.warnings.isNotEmpty()) {
            Text(
                text = formatPrepareSummary(context, prepareState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PrepareActionStrip(
                state = prepareState,
                onCancel = { EngineDataPreparer.cancel(context) },
                onShowDetails = { prepareErrorDetails = prepareState.redactedError }
            )
        }
    }

    dialogReport?.let { report ->
        DiagnosticReportDialog(
            title = stringResource(R.string.developer_capability_report_title),
            confirmLabel = stringResource(R.string.developer_copy_report),
            report = report,
            onCopy = { copyDiagnosticReport(context, report) },
            onDismiss = { dialogReport = null }
        )
    }

    prepareErrorDetails?.let { details ->
        DiagnosticReportDialog(
            title = stringResource(R.string.developer_prepare_failed),
            confirmLabel = stringResource(R.string.developer_prepare_copy_details),
            report = details,
            onCopy = {
                copyTextWithToast(context, "Blaze Prepare Error", details, "Prepare details copied")
            },
            onDismiss = { prepareErrorDetails = null }
        )
    }
}

@Composable
private fun DiagnosticReportDialog(
    title: String,
    confirmLabel: String,
    report: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            SelectionContainer(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
            ) {
                Text(
                    text = report,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCopy) {
                Text(text = confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.generic_close))
            }
        }
    )
}
