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

import io.github.kaito65452.blazelauncher.rustruntime.ui.RustEditionUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperModeTest {
    @Test
    fun developerModeIsDisabledAndEntryPointsHiddenByDefault() {
        assertFalse(DeveloperModeDefaults.DEFAULT_ENABLED)

        val defaultVisibility = DeveloperModeManager.visibility(DeveloperModeDefaults.DEFAULT_ENABLED)
        assertFalse(defaultVisibility.showDeveloperSection)
        assertFalse(defaultVisibility.canOpenCapabilityReport)
        assertFalse(defaultVisibility.canLaunchRustSpike)
        assertFalse(defaultVisibility.canCopyDiagnosticReport)
        assertFalse(defaultVisibility.canToggleDeveloperModeOff)

        val defaultRustState = RustEditionUiState()
        assertFalse(defaultRustState.isDeveloperMode)
        assertFalse(defaultRustState.canShowDiagnostics)
    }

    @Test
    fun fiveTapsUnlockDeveloperModeWithCountdownToastAfterSecondTap() {
        var nowMs = 1_000L
        var enabled = false
        val tracker = DeveloperUnlockTracker(
            clockMs = { nowMs },
            isAlreadyEnabled = { enabled },
            onUnlock = { enabled = true }
        )

        val tap1 = tracker.onVersionTapped()
        assertEquals(VersionTapOutcome.Silent(1), tap1)
        assertFalse(enabled)

        nowMs += 200L
        val tap2 = tracker.onVersionTapped()
        assertEquals(
            VersionTapOutcome.Countdown(3, "3 more taps to enable developer mode"),
            tap2
        )
        assertFalse(enabled)

        nowMs += 200L
        val tap3 = tracker.onVersionTapped()
        assertEquals(
            VersionTapOutcome.Countdown(2, "2 more taps to enable developer mode"),
            tap3
        )
        assertFalse(enabled)

        nowMs += 200L
        val tap4 = tracker.onVersionTapped()
        assertEquals(
            VersionTapOutcome.Countdown(1, "1 more tap to enable developer mode"),
            tap4
        )
        assertFalse(enabled)

        nowMs += 200L
        val tap5 = tracker.onVersionTapped()
        assertEquals(VersionTapOutcome.Unlocked("Developer mode enabled"), tap5)
        assertTrue(enabled)

        val unlockedVisibility = DeveloperModeManager.visibility(enabled)
        assertTrue(unlockedVisibility.showDeveloperSection)
        assertTrue(unlockedVisibility.canOpenCapabilityReport)
        assertTrue(unlockedVisibility.canLaunchRustSpike)
        assertTrue(unlockedVisibility.canCopyDiagnosticReport)
        assertTrue(unlockedVisibility.canToggleDeveloperModeOff)

        val tap6 = tracker.onVersionTapped()
        assertEquals(
            VersionTapOutcome.AlreadyEnabled("Developer mode is already enabled"),
            tap6
        )
    }

    @Test
    fun tapWindowExpiryResetsCounter() {
        var nowMs = 1_000L
        var enabled = false
        val tracker = DeveloperUnlockTracker(
            clockMs = { nowMs },
            isAlreadyEnabled = { enabled },
            onUnlock = { enabled = true }
        )

        assertEquals(VersionTapOutcome.Silent(1), tracker.onVersionTapped())
        nowMs += 100L
        assertTrue(tracker.onVersionTapped() is VersionTapOutcome.Countdown)

        nowMs += DeveloperModeDefaults.TAP_WINDOW_MS + 1L
        assertEquals(VersionTapOutcome.Silent(1), tracker.onVersionTapped())
        assertFalse(enabled)
    }

    @Test
    fun togglingOffHidesAllDiagnosticEntryPoints() {
        val enabledVisibility = DeveloperModeManager.visibility(true)
        assertTrue(enabledVisibility.showDeveloperSection)

        val disabledVisibility = DeveloperModeManager.visibility(false)
        assertFalse(disabledVisibility.showDeveloperSection)
        assertFalse(disabledVisibility.canOpenCapabilityReport)
        assertFalse(disabledVisibility.canLaunchRustSpike)
        assertFalse(disabledVisibility.canCopyDiagnosticReport)
        assertFalse(disabledVisibility.canToggleDeveloperModeOff)
    }
}
