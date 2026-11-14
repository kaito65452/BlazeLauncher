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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RustSessionHandoffTest {
    @Test
    fun normalizeUuidSupportsDashedAndUndashed32Hex() {
        val normalized = RustSessionHandoff.normalizeUuidOrNull("12345678-90AB-CDEF-1234-567890ABCDEF")
        assertEquals("1234567890abcdef1234567890abcdef", normalized)
        assertEquals(
            "1234567890abcdef1234567890abcdef",
            RustSessionHandoff.normalizeUuidOrNull("1234567890abcdef1234567890abcdef")
        )
        assertNull(RustSessionHandoff.normalizeUuidOrNull("not-a-uuid"))
        assertNull(RustSessionHandoff.normalizeUuidOrNull(""))
    }

    @Test
    fun handoffSessionRejectsInvalidInputsWithoutInvokingBridge() {
        var called = false
        val bridge: (String, String, String) -> Boolean = { _, _, _ ->
            called = true
            true
        }

        assertFalse(RustSessionHandoff.handoffSession("", "1234567890abcdef1234567890abcdef", "tok", bridge).accepted)
        assertFalse(RustSessionHandoff.handoffSession("PlayerNameTooLong1", "1234567890abcdef1234567890abcdef", "tok", bridge).accepted)
        assertFalse(RustSessionHandoff.handoffSession("Steve", "bad-uuid", "tok", bridge).accepted)
        assertFalse(RustSessionHandoff.handoffSession("Steve", "1234567890abcdef1234567890abcdef", "", bridge).accepted)
        assertFalse(RustSessionHandoff.handoffSession("Steve", "1234567890abcdef1234567890abcdef", "0", bridge).accepted)
        assertFalse("Bridge must not be invoked for invalid session inputs", called)
    }

    @Test
    fun handoffSessionPassesNormalizedArgsAndRedactsSummary() {
        var passedUser = ""
        var passedUuid = ""
        var passedSecret = ""
        val secretToken = "eyJverySecretTokenValue.abc123.sig"

        val result = RustSessionHandoff.handoffSession(
            user = "  Alex  ",
            uuid = "12345678-90AB-CDEF-1234-567890ABCDEF",
            accessToken = secretToken
        ) { u, id, tok ->
            passedUser = u
            passedUuid = id
            passedSecret = tok
            true
        }

        assertTrue(result.accepted)
        assertEquals("Alex", passedUser)
        assertEquals("1234567890abcdef1234567890abcdef", passedUuid)
        assertEquals(secretToken, passedSecret)
        assertFalse("Summary must never expose raw token", result.summary.contains(secretToken))
        assertTrue(result.summary.contains("uuidPrefix=12345678"))
    }

    @Test
    fun handoffSessionRedactsBridgeExceptionMessageContainingTokens() {
        val secretToken = "super_secret_mc_token_999"
        val result = RustSessionHandoff.handoffSession(
            user = "Steve",
            uuid = "1234567890abcdef1234567890abcdef",
            accessToken = secretToken
        ) { _, _, _ ->
            throw IllegalStateException("Authorization: Bearer $secretToken {\"access_token\":\"$secretToken\"}")
        }

        assertFalse(result.accepted)
        assertFalse(result.summary.contains(secretToken))
        assertTrue(result.summary.contains("[REDACTED]"))
    }

    @Test
    fun diagnosticRedactionStripsJwtXblAndJsonTokensWhileKeepingWgpuLines() {
        val rawLog = """
            01-01 12:00:00.001 I Horizon: BlazeWgpu: selected backend=Vulkan adapter="Mali-G76" device_type=IntegratedGpu
            01-01 12:00:00.002 I Horizon: Authorization: Bearer raw_bearer_secret
            01-01 12:00:00.003 I Horizon: XBL3.0 x=uhs123;eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3OCJ9.sigpart
            01-01 12:00:00.004 I Horizon: {"access_token":"secret_json_tok","ok":true}
        """.trimIndent()

        val redacted = RustDiagnosticsCollector.redactDiagnosticText(rawLog)

        assertTrue(redacted.contains("backend=Vulkan adapter=\"Mali-G76\""))
        assertFalse(redacted.contains("raw_bearer_secret"))
        assertFalse(redacted.contains("uhs123"))
        assertFalse(redacted.contains("eyJhbGciOiJSUzI1NiJ9"))
        assertFalse(redacted.contains("secret_json_tok"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun readStarlingDiagnosticTailReadsSessionAndLogTail() {
        val tempDir = Files.createTempDirectory("starling_diag_test").toFile()
        try {
            val perfDir = tempDir.resolve("performance").apply { mkdirs() }
            perfDir.resolve("current-session.json").writeText("{\"phase\":\"native_start\",\"clean_exit\":false}")
            perfDir.resolve("diagnostic.log").writeText("line1\nBlazeWgpu: selected backend=Vulkan\n")

            val tail = RustDiagnosticsCollector.readStarlingDiagnosticTail(tempDir)
            assertTrue(tail.contains("current-session.json:"))
            assertTrue(tail.contains("BlazeWgpu: selected backend=Vulkan"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun copyReportOutputExcludesFakeAccessTokenAcrossHandoffAndEngineDiagnostics() {
        val rootDir = Files.createTempDirectory("blaze_report_redaction_test").toFile()
        try {
            val filesDir = rootDir.resolve("files").apply { mkdirs() }
            val statusDir = rootDir.resolve("no_backup").apply { mkdirs() }
            val fakeJwtToken = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJmYWtlLXV1aWQifQ.sig_secret_987"
            val fakeOpaqueToken = "M.C501_BLZ.FakeAccessTokenValue_777"

            val acceptedHandoff = RustSessionHandoff.handoffSession(
                user = "Alex",
                uuid = "12345678-90AB-CDEF-1234-567890ABCDEF",
                accessToken = fakeJwtToken
            ) { _, _, _ -> true }
            val failedHandoff = RustSessionHandoff.handoffSession(
                user = "Alex",
                uuid = "12345678-90AB-CDEF-1234-567890ABCDEF",
                accessToken = fakeOpaqueToken
            ) { _, _, _ ->
                throw IllegalStateException("Bearer $fakeOpaqueToken {\"access_token\":\"$fakeOpaqueToken\"}")
            }

            statusDir.resolve("rust_spike_last_status.txt").writeText(
                RustDiagnosticsCollector.redactDiagnosticText(
                    """
                    [2026-11-14 12:00:00.000] session_handoff: ${acceptedHandoff.summary}
                    [2026-11-14 12:00:01.000] session_handoff_err: ${failedHandoff.summary}
                    [2026-11-14 12:00:02.000] raw_leak_guard: Authorization: Bearer $fakeOpaqueToken XBL3.0 x=uhs999;$fakeJwtToken
                    """.trimIndent()
                )
            )
            val perfDir = filesDir.resolve("performance").apply { mkdirs() }
            perfDir.resolve("diagnostic.log").writeText(
                "Horizon: {\"access_token\":\"$fakeOpaqueToken\",\"refresh_token\":\"$fakeJwtToken\"}\n"
            )

            val report = RustDiagnosticsCollector.collectRustDiagnosticsFromDirs(
                filesDir = filesDir,
                statusDir = statusDir,
                historicalExits = "2026-11-14 12:00:05.000 pid=42 reason=10 status=0 desc=Bearer $fakeOpaqueToken",
                logcatProvider = {
                    "01-01 12:00:00.100 I Horizon: Authorization: Bearer $fakeOpaqueToken\n" +
                        "01-01 12:00:00.200 I Horizon: {\"access_token\":\"$fakeJwtToken\"}"
                }
            )

            assertFalse("Raw JWT fake token must be absent from Copy report output", report.contains(fakeJwtToken))
            assertFalse("Raw opaque fake token must be absent from Copy report output", report.contains(fakeOpaqueToken))
            assertTrue(report.contains("=== Engine Data Check ==="))
            assertTrue(report.contains("Engine data: expected path="))
            assertTrue(report.contains("engine-data.zip=missing"))
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun engineDataDiagnosisReportsExpectedPathAndFilePresence() {
        val filesDir = Files.createTempDirectory("blaze_engine_data_diag_test").toFile()
        try {
            val missingDiag = RustDiagnosticsCollector.collectEngineDataDiagnosis(filesDir)
            assertTrue(missingDiag.contains("Engine data: expected path="))
            assertTrue(missingDiag.contains("engine-data.zip=missing"))
            assertTrue(missingDiag.contains("horizon-resources.json=missing"))
            assertTrue(missingDiag.contains("android-downloads-v2.json=missing"))

            filesDir.resolve("engine-data.zip").writeBytes(byteArrayOf(1, 2, 3, 4))
            val markerDir = filesDir.resolve(".local/horizon-client/26.3").apply { mkdirs() }
            markerDir.resolve("horizon-resources.json").writeText("{}")

            val partialDiag = RustDiagnosticsCollector.collectEngineDataDiagnosis(filesDir)
            assertTrue(partialDiag.contains("engine-data.zip=present (4 bytes)"))
            assertTrue(partialDiag.contains("horizon-resources.json=present"))
            assertTrue(partialDiag.contains("android-downloads-v2.json=missing"))
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
