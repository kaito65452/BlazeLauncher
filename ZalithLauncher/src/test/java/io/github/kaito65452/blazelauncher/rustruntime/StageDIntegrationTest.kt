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

import com.movtery.zalithlauncher.game.version.installed.GameRuntime
import com.movtery.zalithlauncher.game.version.installed.VersionConfig
import com.movtery.zalithlauncher.utils.GSON
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StageDIntegrationTest {
    @Test
    fun rustEditionCatalog_readsSupportedVersionFromResearchLockJson() {
        val sampleLock = """
            {
              "minecraft": {
                "version": "26.3",
                "manifest_sha256": "deadbeef"
              }
            }
        """.trimIndent()
        val parsed = RustEditionCatalog.parseSupportedVersionFromLockJson(sampleLock)
        assertEquals("26.3", parsed)
        assertTrue(RustEditionCatalog.isSupportedVersionMatch("26.3", parsed))
        assertTrue(RustEditionCatalog.isSupportedVersionMatch(" 26.3 ", parsed))
        assertFalse(RustEditionCatalog.isSupportedVersionMatch("1.21.4", parsed))
        assertFalse(RustEditionCatalog.isSupportedVersionMatch(null, parsed))
        assertNull(RustEditionCatalog.parseSupportedVersionFromLockJson("{}"))
    }

    @Test
    fun versionConfig_defaultsToJavaWhenRuntimeFieldMissingOrNull_andRoundTripsRust() {
        val tempDir = Files.createTempDirectory("staged_vconfig").toFile()
        try {
            // Existing version.config without "runtime" must stay backward-compatible as JAVA
            val legacyJson = """{"isolationType":"ENABLE","ramAllocation":2048}"""
            val legacy = GSON.fromJson(legacyJson, VersionConfig::class.java)
            legacy.setVersionPath(tempDir)
            assertEquals(GameRuntime.JAVA.wireName, legacy.runtime)
            assertEquals(GameRuntime.JAVA, legacy.getRuntime())

            // Explicit null or unrecognized runtime must safely normalize to JAVA
            val nullJson = """{"runtime":null}"""
            val nullRuntime = GSON.fromJson(nullJson, VersionConfig::class.java)
            assertEquals(GameRuntime.JAVA, nullRuntime.getRuntime())

            // Explicit rust runtime serializes and parses cleanly
            val rustConfig = VersionConfig(File(tempDir, "26.3-Rust"), runtime = GameRuntime.RUST.wireName)
            assertEquals(GameRuntime.RUST, rustConfig.getRuntime())
            val encoded = GSON.toJson(rustConfig)
            assertTrue(
                "Expected runtime=rust in JSON: $encoded",
                Regex(""""runtime"\s*:\s*"rust"""").containsMatchIn(encoded)
            )
            val decoded = GSON.fromJson(encoded, VersionConfig::class.java)
            assertEquals(GameRuntime.RUST, decoded.getRuntime())
            assertEquals(GameRuntime.RUST, rustConfig.copy().getRuntime())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun rustLaunchDispatcher_rejectsOfflineNonMicrosoftUnverifiedAndMissingEngineData() {
        // Offline login flag set -> denied
        val offlineRes = RustLaunchDispatcher.evaluatePreconditions(
            runtime = GameRuntime.RUST,
            offlineAccountLogin = true,
            isMicrosoftAccount = true,
            hasLiveToken = true,
            hasNetwork = true,
            launchCheckNeeded = false,
            isEngineDataPrepared = true
        )
        assertTrue(offlineRes is RustLaunchPrecondition.Denied)

        // Non-Microsoft (local / offline / third-party auth) -> denied
        val localRes = RustLaunchDispatcher.evaluatePreconditions(
            runtime = GameRuntime.RUST,
            offlineAccountLogin = false,
            isMicrosoftAccount = false,
            hasLiveToken = true,
            hasNetwork = true,
            launchCheckNeeded = false,
            isEngineDataPrepared = true
        )
        assertTrue(localRes is RustLaunchPrecondition.Denied)

        // Offline network when launch check still required -> denied online verification
        val unverifiedOffline = RustLaunchDispatcher.evaluatePreconditions(
            runtime = GameRuntime.RUST,
            offlineAccountLogin = false,
            isMicrosoftAccount = true,
            hasLiveToken = true,
            hasNetwork = false,
            launchCheckNeeded = true,
            isEngineDataPrepared = true
        )
        assertTrue(unverifiedOffline is RustLaunchPrecondition.Denied)
        assertTrue((unverifiedOffline as RustLaunchPrecondition.Denied).requiresOnlineVerification)

        // Missing engine-data -> denied
        val missingData = RustLaunchDispatcher.evaluatePreconditions(
            runtime = GameRuntime.RUST,
            offlineAccountLogin = false,
            isMicrosoftAccount = true,
            hasLiveToken = true,
            hasNetwork = true,
            launchCheckNeeded = false,
            isEngineDataPrepared = false
        )
        assertTrue(missingData is RustLaunchPrecondition.Denied)

        // Verified Microsoft account + prepared engine data -> ready
        val ready = RustLaunchDispatcher.evaluatePreconditions(
            runtime = GameRuntime.RUST,
            offlineAccountLogin = false,
            isMicrosoftAccount = true,
            hasLiveToken = true,
            hasNetwork = true,
            launchCheckNeeded = false,
            isEngineDataPrepared = true
        )
        assertEquals(RustLaunchPrecondition.Ready, ready)
    }

    @Test
    fun versionConfig_loadOnDiskPreservesRuntimeFieldAcrossParse() {
        val tempRoot = Files.createTempDirectory("staged_disk_cfg").toFile()
        try {
            val versionDir = File(tempRoot, "26.3-Rust").apply { mkdirs() }
            val cfgFile = File(versionDir, "version.config")
            val cfg = VersionConfig(versionDir, runtime = GameRuntime.RUST.wireName)
            cfgFile.writeText(GSON.toJson(cfg))
            val reloaded = GSON.fromJson(cfgFile.readText(), VersionConfig::class.java).apply {
                setVersionPath(versionDir)
            }
            assertEquals(GameRuntime.RUST, reloaded.getRuntime())

            // Existing legacy JSON on disk without "runtime" must default to JAVA
            val legacyFile = File(versionDir, "legacy.config")
            legacyFile.writeText("""{"isolationType":"DISABLE","ramAllocation":1024}""")
            val legacyReloaded = GSON.fromJson(legacyFile.readText(), VersionConfig::class.java).apply {
                setVersionPath(versionDir)
            }
            assertEquals(GameRuntime.JAVA, legacyReloaded.getRuntime())
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
