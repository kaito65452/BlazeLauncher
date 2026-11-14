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

package io.github.kaito65452.blazelauncher.developer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.movtery.zalithlauncher.setting.AllSettings
import io.github.kaito65452.blazelauncher.rustruntime.DeviceCapabilityReport

/**
 * Runtime Developer Mode preferences, unlock gesture state machine, and diagnostics-only state.
 *
 * Strictly contains hardware/engine diagnostic helpers only.
 */
object DeveloperModeDefaults {
    const val DEFAULT_ENABLED: Boolean = false
    const val REQUIRED_VERSION_TAPS: Int = 5
    const val TOAST_AFTER_TAPS: Int = 2
    const val TAP_WINDOW_MS: Long = 3_000L
    const val MESSAGE_ENABLED: String = "Developer mode enabled"
    const val MESSAGE_ALREADY_ENABLED: String = "Developer mode is already enabled"

    fun formatRemainingTaps(remaining: Int): String {
        val unit = if (remaining == 1) "tap" else "taps"
        return "$remaining more $unit to enable developer mode"
    }
}

data class DeveloperDiagnosticsVisibility(
    val showDeveloperSection: Boolean,
    val canOpenCapabilityReport: Boolean,
    val canLaunchRustSpike: Boolean,
    val canCopyDiagnosticReport: Boolean,
    val canToggleDeveloperModeOff: Boolean
)

sealed interface VersionTapOutcome {
    data class Silent(val tapsRecorded: Int) : VersionTapOutcome
    data class Countdown(val remainingTaps: Int, val message: String) : VersionTapOutcome
    data class Unlocked(val message: String) : VersionTapOutcome
    data class AlreadyEnabled(val message: String) : VersionTapOutcome
}

class DeveloperUnlockTracker(
    private val requiredTaps: Int = DeveloperModeDefaults.REQUIRED_VERSION_TAPS,
    private val toastAfterTaps: Int = DeveloperModeDefaults.TOAST_AFTER_TAPS,
    private val tapWindowMs: Long = DeveloperModeDefaults.TAP_WINDOW_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val isAlreadyEnabled: () -> Boolean,
    private val onUnlock: () -> Unit
) {
    private var tapCount: Int = 0
    private var lastTapTimestampMs: Long = 0L

    fun onVersionTapped(): VersionTapOutcome {
        if (isAlreadyEnabled()) {
            return VersionTapOutcome.AlreadyEnabled(DeveloperModeDefaults.MESSAGE_ALREADY_ENABLED)
        }

        val now = clockMs()
        if (tapCount == 0 || (now - lastTapTimestampMs) > tapWindowMs) {
            tapCount = 1
        } else {
            tapCount += 1
        }
        lastTapTimestampMs = now

        if (tapCount >= requiredTaps) {
            tapCount = 0
            lastTapTimestampMs = 0L
            onUnlock()
            return VersionTapOutcome.Unlocked(DeveloperModeDefaults.MESSAGE_ENABLED)
        }

        val remaining = requiredTaps - tapCount
        return if (tapCount >= toastAfterTaps) {
            VersionTapOutcome.Countdown(
                remainingTaps = remaining,
                message = DeveloperModeDefaults.formatRemainingTaps(remaining)
            )
        } else {
            VersionTapOutcome.Silent(tapCount)
        }
    }

    fun reset() {
        tapCount = 0
        lastTapTimestampMs = 0L
    }
}

object DeveloperModeManager {
    fun visibility(enabled: Boolean = isEnabledState()): DeveloperDiagnosticsVisibility {
        return DeveloperDiagnosticsVisibility(
            showDeveloperSection = enabled,
            canOpenCapabilityReport = enabled,
            canLaunchRustSpike = enabled,
            canCopyDiagnosticReport = enabled,
            canToggleDeveloperModeOff = enabled
        )
    }

    fun isEnabledState(): Boolean = AllSettings.developerMode.state

    fun setEnabled(enabled: Boolean) {
        AllSettings.developerMode.save(enabled)
    }
}

object DeveloperDiagnosticsStore {
    var rustActivityStatus by mutableStateOf("Not launched in this session")
        private set

    var lastReportOutput by mutableStateOf<String?>(null)
        private set

    fun recordRustActivityEvent(status: String) {
        rustActivityStatus = status
    }

    fun collectAndBuildReport(context: Context): String {
        val capability = DeviceCapabilityReport.format(
            DeviceCapabilityReport.collect(context)
        )
        val combined = buildString {
            appendLine(capability.trimEnd())
            appendLine()
            appendLine("=== RustActivity (:rust) Session ===")
            appendLine("Last :rust event: $rustActivityStatus")
        }
        lastReportOutput = combined
        return combined
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}

object DeveloperUnlockHost {
    private var activeToast: Toast? = null

    fun createTracker(): DeveloperUnlockTracker = DeveloperUnlockTracker(
        isAlreadyEnabled = { DeveloperModeManager.isEnabledState() },
        onUnlock = { DeveloperModeManager.setEnabled(true) }
    )

    fun handleTap(context: Context, tracker: DeveloperUnlockTracker) {
        val message = when (val outcome = tracker.onVersionTapped()) {
            is VersionTapOutcome.Silent -> null
            is VersionTapOutcome.Countdown -> outcome.message
            is VersionTapOutcome.Unlocked -> outcome.message
            is VersionTapOutcome.AlreadyEnabled -> outcome.message
        } ?: return

        activeToast?.cancel()
        activeToast = Toast.makeText(context, message, Toast.LENGTH_SHORT).also {
            it.show()
        }
    }
}
