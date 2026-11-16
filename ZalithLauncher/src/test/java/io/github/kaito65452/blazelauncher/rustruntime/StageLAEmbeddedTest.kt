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

package io.github.kaito65452.blazelauncher.rustruntime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StageLAEmbeddedTest {
    @Test
    fun rustActivityStartupDecision_neverFallsBackWithoutPendingSession() {
        assertEquals(
            RustActivity.StartupDecision.Proceed,
            RustActivity.decideStartup(
                hasSavedInstanceState = false,
                directLaunch = true,
                sessionAccepted = true,
                embeddedUiStripped = true
            )
        )

        val resumedWithoutSession = RustActivity.decideStartup(
            hasSavedInstanceState = true,
            directLaunch = true,
            sessionAccepted = true,
            embeddedUiStripped = true
        )
        assertTrue(resumedWithoutSession is RustActivity.StartupDecision.ExitToLauncher)
        assertEquals(
            RustActivity.REASON_NO_PENDING_SESSION,
            (resumedWithoutSession as RustActivity.StartupDecision.ExitToLauncher).reasonCode
        )

        val missingSession = RustActivity.decideStartup(
            hasSavedInstanceState = false,
            directLaunch = true,
            sessionAccepted = false,
            embeddedUiStripped = true
        )
        assertTrue(missingSession is RustActivity.StartupDecision.ExitToLauncher)

        val nonDirectEmbeddedWithoutSession = RustActivity.decideStartup(
            hasSavedInstanceState = false,
            directLaunch = false,
            sessionAccepted = false,
            embeddedUiStripped = true
        )
        assertTrue(nonDirectEmbeddedWithoutSession is RustActivity.StartupDecision.ExitToLauncher)
    }

    @Test
    fun blazeEngineSettings_clampsBoundsAndPersistsJsonSchema() {
        val tempRoot = Files.createTempDirectory("stage_la_settings").toFile()
        try {
            val payload = BlazeEngineSettings.fromValues(
                renderScalePercent = 10,
                touchLookSensitivityPercent = 500,
                showControls = false
            )
            assertEquals(1, payload.schema)
            assertEquals(25, payload.renderScalePercent)
            assertEquals(3.0f, payload.touchLookSensitivity, 0.001f)
            assertFalse(payload.showControls)
            assertEquals("blaze_launcher", payload.source)

            assertTrue(BlazeEngineSettings.writeToDir(tempRoot, payload))
            val savedFile = File(tempRoot, BlazeEngineSettings.SETTINGS_FILE_NAME)
            assertTrue(savedFile.isFile)
            val parsed = BlazeEngineSettings.fromJsonOrNull(savedFile.readText())
            assertNotNull(parsed)
            assertEquals(25, parsed!!.renderScalePercent)
            assertEquals(3.0f, parsed.touchLookSensitivity, 0.001f)
            assertFalse(parsed.showControls)

            val desc = BlazeEngineSettings.describeSettingsSource(tempRoot)
            assertTrue(desc.contains("blaze_launcher"))
            assertTrue(desc.contains("scale=25%"))
            assertTrue(desc.contains("controls=false"))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun copyReportIncludesEmbeddedUiPolicyLastExitReasonAndSettingsSource() {
        val tempRoot = Files.createTempDirectory("stage_la_diag").toFile()
        try {
            val filesDir = File(tempRoot, "files").apply { mkdirs() }
            val statusDir = File(tempRoot, "no_backup").apply { mkdirs() }
            BlazeEngineSettings.writeToDir(
                filesDir,
                BlazeEngineSettings.fromValues(
                    renderScalePercent = 85,
                    touchLookSensitivityPercent = 140,
                    showControls = true
                )
            )
            val fakeUuid = "01234567-89ab-cdef-0123-456789abcdef"
            RustDiagnosticsCollector.recordExitReasonToDir(
                statusDir,
                reasonCode = 2,
                message = "Back pressed on title screen for $fakeUuid"
            )

            val report = RustDiagnosticsCollector.collectRustDiagnosticsFromDirs(
                filesDir = filesDir,
                statusDir = statusDir,
                historicalExits = "",
                logcatProvider = { "" }
            )

            assertTrue(report.contains("=== Engine UI & Embedded Policy ==="))
            assertTrue(report.contains("engine ui: launcher window disabled | embedded feature on"))
            assertTrue(report.contains("last_exit_reason:"))
            assertTrue(report.contains("TITLE_BACK(2)"))
            assertTrue(report.contains("settings_source: blaze_launcher"))
            assertTrue(report.contains("scale=85%"))
            assertFalse("Full UUID leaked in exit reason", report.contains(fakeUuid))
            assertTrue("Expected truncated UUID prefix", report.contains("01234567..."))
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
