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

import android.app.Activity
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class StageCDiagnosticsTest {
    @Test
    fun sanitizeDocumentPickerUri_validContentUriAccepted() {
        val uri = "content://com.android.externalstorage.documents/document/primary%3Atest.zip"
        assertEquals(uri, RustActivity.sanitizeDocumentPickerUri(Activity.RESULT_OK, "  $uri  "))
    }

    @Test
    fun sanitizeDocumentPickerUri_rejectsNonOkOrNonContentOrOversize() {
        val valid = "content://com.example/doc/1"
        assertNull(RustActivity.sanitizeDocumentPickerUri(Activity.RESULT_CANCELED, valid))
        assertNull(RustActivity.sanitizeDocumentPickerUri(Activity.RESULT_OK, "file:///sdcard/test.zip"))
        assertNull(RustActivity.sanitizeDocumentPickerUri(Activity.RESULT_OK, ""))
        assertNull(RustActivity.sanitizeDocumentPickerUri(Activity.RESULT_OK, null))

        val oversized = "content://a/" + "x".repeat(RustActivity.MAX_DOCUMENT_URI_LENGTH)
        assertNull(RustActivity.sanitizeDocumentPickerUri(Activity.RESULT_OK, oversized))
    }

    @Test
    fun computeSideGestureExclusionStrips_respects200DpCapAndEdges() {
        val density = 3.0f
        val maxPx = (RustActivity.MAX_SYSTEM_GESTURE_STRIP_DP * density).toInt()
        val strips = RustActivity.computeSideGestureExclusionStrips(
            widthPx = 2280,
            heightPx = 1080,
            density = density
        )
        assertEquals(2, strips.size)
        val left = strips[0]
        val right = strips[1]
        assertEquals(0, left.left)
        assertEquals(2280, right.right)
        assertTrue("left strip height ${left.height} exceeds 200dp=$maxPx", left.height <= maxPx)
        assertTrue("right strip height ${right.height} exceeds 200dp=$maxPx", right.height <= maxPx)
        assertTrue(left.height > 0 && right.height > 0)
        assertTrue(left.right < right.left)

        // Short landscape strip smaller than 200dp uses full height without exceeding bounds.
        val shortStrips = RustActivity.computeSideGestureExclusionStrips(800, 300, 2.0f)
        assertEquals(2, shortStrips.size)
        assertEquals(0, shortStrips[0].top)
        assertEquals(300, shortStrips[0].bottom)

        // Invalid dimensions produce no strips.
        assertTrue(RustActivity.computeSideGestureExclusionStrips(0, 1080, 2.0f).isEmpty())
        assertTrue(RustActivity.computeSideGestureExclusionStrips(1920, 1080, 0f).isEmpty())
    }

    @Test
    fun extractRenderStartupSummary_parsesBlazePerfAndExitReasonWithoutSecretLeaks() {
        val tempRoot = Files.createTempDirectory("stagec_diag").toFile()
        try {
            val filesDir = File(tempRoot, "files").apply { mkdirs() }
            val statusDir = File(tempRoot, "no_backup").apply { mkdirs() }
            val perfDir = File(filesDir, "performance").apply { mkdirs() }
            val diagLog = File(perfDir, "diagnostic.log")
            val secretToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig12345"
            val fakeUuid = "01234567-89ab-cdef-0123-456789abcdef"
            diagLog.writeText(
                listOf(
                    "Horizon renderer: Mali-G76 MC12 (Mali, Vulkan)",
                    "BlazePerf pipelines_created_ms=412 initial=true multi_draw=on occlusion_culling=off gpu_timer=on",
                    "token=$secretToken uuid=$fakeUuid"
                ).joinToString("\n")
            )
            val logcat = "01-01 12:00:00.123  1234  1234 I Horizon : BlazePerf surface_to_first_frame_ms=895 backend=Vulkan adapter=Mali-G76 MC12"
            val historicalExits = "2026-11-15 04:00:00.000 pid=4201 reason=5 status=11 desc=SIGSEGV in render"

            val report = RustDiagnosticsCollector.collectRustDiagnosticsFromDirs(
                filesDir = filesDir,
                statusDir = statusDir,
                historicalExits = historicalExits,
                logcatProvider = { logcat }
            )

            assertTrue(report.contains("=== Render Startup & Exit Summary ==="))
            assertTrue(report.contains("pipelines_created_ms=412"))
            assertTrue(report.contains("surface_to_first_frame_ms=895 backend=Vulkan adapter=Mali-G76 MC12"))
            assertTrue(report.contains("last_rust_exit=CRASH_NATIVE(5)"))
            assertFalse("JWT token leaked in report", report.contains(secretToken))
            assertFalse("Full UUID leaked in report", report.contains(fakeUuid))
            assertTrue("Expected truncated UUID prefix", report.contains("01234567..."))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun extractRenderStartupSummary_recoversStartupPerfWhenTailEvictedStartupLogs() {
        val tempRoot = Files.createTempDirectory("stagec_perf_recover").toFile()
        try {
            val filesDir = File(tempRoot, "files").apply { mkdirs() }
            val statusDir = File(tempRoot, "no_backup").apply { mkdirs() }
            val report = RustDiagnosticsCollector.collectRustDiagnosticsFromDirs(
                filesDir = filesDir,
                statusDir = statusDir,
                logcatProvider = { "01-01 12:05:00.000 1234 1234 I Horizon : gameplay frame log only" },
                renderPerfLogcatProvider = {
                    listOf(
                        "01-01 12:00:01.000 1234 1234 I Horizon : BlazePerf pipelines_created_ms=510 initial=true multi_draw=on occlusion_culling=on gpu_timer=off",
                        "01-01 12:00:02.000 1234 1234 I Horizon : BlazePerf surface_to_first_frame_ms=920 backend=Vulkan adapter=Mali-G76 MC12"
                    ).joinToString("\n")
                }
            )
            assertTrue(report.contains("pipelines_created_ms=510"))
            assertTrue(report.contains("surface_to_first_frame_ms=920 backend=Vulkan adapter=Mali-G76 MC12"))
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
