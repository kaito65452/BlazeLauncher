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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class EngineDataPrepareTest {
    @Test
    fun prepareConfigAndEnvelopeJsonRoundTrip() {
        val cfg = PrepareConfigJson(
            filesDir = "/tmp/files",
            gameHome = "/tmp/game",
            lockPath = "/tmp/lock.json",
            controlsManifestPath = "/tmp/controls.json",
            paritySourcesDir = "/tmp/parity",
            allowLoopback = true,
            downloadWorkers = 4
        )
        val encoded = BlazePrepareNative.encodeConfig(cfg)
        assertTrue(encoded.contains("\"files_dir\":\"/tmp/files\""))
        assertTrue(encoded.contains("\"game_home\":\"/tmp/game\""))
        assertTrue(encoded.contains("\"allow_loopback\":true"))

        val errEnv = BlazePrepareNative.parseEnvelope(
            """{"ok":false,"error":"SHA-1 mismatch on client.jar","peak_rss_kb":24576}""",
            Phase1OutputJson::class.java
        )
        assertTrue(errEnv is PrepareEnvelope.Failure)
        val failure = errEnv as PrepareEnvelope.Failure
        assertEquals("SHA-1 mismatch on client.jar", failure.error)
        assertEquals(24576L, failure.peakRssKb)

        val okEnv = BlazePrepareNative.parseEnvelope(
            """{"ok":true,"data":{"target_resources_dir":"/tmp/res","engine_data_zip":"/tmp/engine-data.zip","engine_data_entries":33,"survival_rules_count":152,"items_count":1400,"elapsed_ms":3210,"peak_rss_kb":32000}}""",
            Phase2OutputJson::class.java
        )
        assertTrue(okEnv is PrepareEnvelope.Success)
        val success = (okEnv as PrepareEnvelope.Success).data
        assertEquals(33L, success.engineDataEntries)
        assertEquals(32000L, success.peakRssKb)
    }

    @Test
    fun exporterPartitionsCoverAll26ParitySourcesWithoutClientLeakOnServer() {
        val serverSet = EngineDataPrepareSupport.SERVER_EXPORTER_FILES.toSet()
        val clientSet = EngineDataPrepareSupport.CLIENT_EXPORTER_FILES.toSet()
        val allMit = (serverSet + clientSet) - "PrepareBatchShim.java"
        assertEquals("Expected all 26 MIT parity .java sources", 26, allMit.size)
        assertFalse(serverSet.contains("VanillaBlockStateBindingOracle.java"))
        assertFalse(serverSet.contains("VanillaMobDataExporter.java"))
        assertTrue(clientSet.contains("VanillaBlockStateBindingOracle.java"))
        assertTrue(clientSet.contains("VanillaCuboidModelOracle.java"))
    }

    @Test
    fun javacArgFileQuotesPathsAndSetsJava25Flags() {
        val argFile = EngineDataPrepareSupport.formatJavacArgFile(
            classpath = "/data/user/0/app/my cp/server.jar:/tmp/a.jar",
            sourcePath = "/data/user/0/app/horizon-parity",
            outputDir = "/data/user/0/app/classes/server",
            javaSourceFiles = listOf(File("/data/user/0/app/horizon-parity/dev/horizonlab/parity/PrepareWorker.java"))
        )
        assertTrue(argFile.contains("--release\n25\n-proc:none"))
        assertTrue(argFile.contains("\"/data/user/0/app/my cp/server.jar:/tmp/a.jar\""))
        assertTrue(argFile.contains("\"/data/user/0/app/horizon-parity/dev/horizonlab/parity/PrepareWorker.java\""))
        assertTrue(EngineDataPrepareSupport.buildCommonJvmFlags(640).contains("-Djava.awt.headless=true"))
        assertTrue(EngineDataPrepareSupport.buildCommonJvmFlags(640).contains("-Xmx640M"))
    }

    @Test
    fun prepareTelemetryAndErrorDetailsRedactTokensAndFullUuids() {
        val tempDir = Files.createTempDirectory("blaze-prepare-telemetry-test").toFile()
        try {
            val dirtyError = EngineDataPrepareSupport.buildRedactedErrorDetails(
                stepName = "jvm_exporters",
                reason = "exit 1 for player 12345678-90ab-cdef-1234-567890abcdef access_token=secretTokValue",
                processLogTail = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig XBL3.0 x=userhash;secret"
            )
            assertFalse(dirtyError.contains("secretTokValue"))
            assertFalse(dirtyError.contains("12345678-90ab-cdef-1234-567890abcdef"))
            assertFalse(dirtyError.contains("eyJhbGciOiJIUzI1NiJ9"))
            assertTrue(dirtyError.contains("12345678..."))

            EngineDataPrepareTelemetry.save(
                tempDir,
                EngineDataPrepareMetrics(
                    status = "ready",
                    minecraftVersion = "26.3",
                    totalMs = 12345L,
                    phaseTimingsMs = mapOf("rust_phase1" to 4000L, "jvm_exporters" to 7000L, "rust_phase2" to 1345L),
                    peakRssKb = 198400L,
                    filesReused = 42L,
                    filesDownloaded = 15L,
                    engineDataEntries = 33L,
                    lastError = "token=shouldNotPersist"
                )
            )
            val loaded = EngineDataPrepareTelemetry.load(tempDir)
            assertNotNull(loaded)
            assertFalse(loaded!!.lastError.contains("shouldNotPersist"))
            val reportText = EngineDataPrepareTelemetry.formatForReport(tempDir)
            assertTrue(reportText.contains("peak_rss_kb=198400"))
            assertTrue(reportText.contains("engine_data_entries=33"))
            assertFalse(reportText.contains("shouldNotPersist"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun kotlinSynthetic4000FileBenchmark() {
        val root = Files.createTempDirectory("blaze-prepare-kotlin-bench").toFile()
        try {
            val inputZip = File(root, "input.jar")
            val extractedDir = File(root, "extracted")
            val outputZip = File(root, "out.zip")

            val t0 = System.nanoTime()
            val expectedCrcs = HashMap<String, Long>(4096)
            ZipOutputStream(BufferedOutputStream(FileOutputStream(inputZip))).use { zos ->
                zos.setLevel(Deflater.BEST_SPEED)
                for (i in 0 until 4000) {
                    val name = "assets/minecraft/models/gen_$i.json"
                    val payload = """{"id":$i,"tag":"minecraft:stone_$i","ok":true}""".toByteArray()
                    val crc = CRC32().apply { update(payload) }.value
                    expectedCrcs[name] = crc
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(payload)
                    zos.closeEntry()
                }
            }
            val t1 = System.nanoTime()

            ZipInputStream(BufferedInputStream(FileInputStream(inputZip))).use { zis ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val dest = File(extractedDir, entry.name)
                    dest.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(dest)).use { out ->
                        while (true) {
                            val r = zis.read(buf)
                            if (r <= 0) break
                            out.write(buf, 0, r)
                        }
                    }
                    zis.closeEntry()
                }
            }
            val t2 = System.nanoTime()

            var verifiedCount = 0
            val buf = ByteArray(16 * 1024)
            extractedDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = file.relativeTo(extractedDir).invariantSeparatorsPath
                val crc = CRC32()
                BufferedInputStream(FileInputStream(file)).use { inp ->
                    while (true) {
                        val r = inp.read(buf)
                        if (r <= 0) break
                        crc.update(buf, 0, r)
                    }
                }
                assertEquals(expectedCrcs[rel], crc.value)
                verifiedCount += 1
            }
            assertEquals(4000, verifiedCount)
            val t3 = System.nanoTime()

            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZip))).use { zos ->
                zos.setLevel(Deflater.BEST_SPEED)
                extractedDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val rel = file.relativeTo(extractedDir).invariantSeparatorsPath
                    zos.putNextEntry(ZipEntry(rel))
                    BufferedInputStream(FileInputStream(file)).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            val t4 = System.nanoTime()

            val rt = Runtime.getRuntime()
            val usedKb = (rt.totalMemory() - rt.freeMemory()) / 1024L
            println(
                "BENCH_KOTLIN_4000 build_ms=${(t1 - t0) / 1_000_000} " +
                    "extract_ms=${(t2 - t1) / 1_000_000} " +
                    "verify_ms=${(t3 - t2) / 1_000_000} " +
                    "zip_ms=${(t4 - t3) / 1_000_000} heap_kb=$usedKb"
            )
            assertTrue(outputZip.length() > 1024)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun localPinRefreshWarningReachesPhase1AndEngineDataCheckReport() {
        val env = BlazePrepareNative.parseEnvelope(
            """{"ok":true,"data":{"work_dir":"/tmp/w","stage_dir":"/tmp/s","target_resources_dir":"/tmp/t","client_resource_files":10,"indexed_assets_copied":2,"controls_ready":1,"worldgen_structures_extracted":1,"worldgen_maps_written":19,"elapsed_ms":120,"peak_rss_kb":4096,"warnings":["Manifest/asset-index pin drift on 26.3 (manifest sha1=9f67e10ed013deeaad20ac3c800652765d493408, assetIndex sha1=e99f9973047785b5a9d52eaf72020b97545053c9; client_sha1 and server_sha1 match upstream pins): local pin refresh, not verified by upstream"]}}""",
            Phase1OutputJson::class.java
        )
        assertTrue(env is PrepareEnvelope.Success)
        val p1 = (env as PrepareEnvelope.Success).data
        assertEquals(1, p1.warnings.size)
        assertTrue(p1.warnings.first().contains("local pin refresh, not verified by upstream"))

        val root = Files.createTempDirectory("blaze-prepare-warning-report-test").toFile()
        try {
            val filesDir = File(root, "files").apply { mkdirs() }
            val statusDir = File(root, "status").apply { mkdirs() }
            EngineDataPrepareTelemetry.save(
                filesDir,
                EngineDataPrepareMetrics(
                    status = "ready",
                    minecraftVersion = "26.3",
                    warnings = p1.warnings,
                    updatedAtMs = 1000L
                )
            )
            val report = RustDiagnosticsCollector.collectRustDiagnosticsFromDirs(
                filesDir = filesDir,
                statusDir = statusDir,
                logcatProvider = { "" }
            )
            val engineCheckIdx = report.indexOf("=== Engine Data Check ===")
            val renderIdx = report.indexOf("=== Render Startup & Exit Summary ===")
            assertTrue(engineCheckIdx >= 0 && renderIdx > engineCheckIdx)
            val engineSection = report.substring(engineCheckIdx, renderIdx)
            assertTrue(
                "Expected local pin refresh warning in Engine Data Check section:\n$engineSection",
                engineSection.contains("local pin refresh, not verified by upstream")
            )
            assertTrue(engineSection.contains("9f67e10ed013deeaad20ac3c800652765d493408"))
            assertTrue(engineSection.contains("e99f9973047785b5a9d52eaf72020b97545053c9"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedPhaseAndRedactedErrorReachBothDetailsAndCopyReport() {
        val root = Files.createTempDirectory("blaze-prepare-failed-phase-test").toFile()
        try {
            val filesDir = File(root, "files").apply { mkdirs() }
            val statusDir = File(root, "status").apply { mkdirs() }
            val failedPhase = "jvm_exporters: Compiling server parity exporters (javac 25)"
            val details = EngineDataPrepareSupport.buildRedactedErrorDetails(
                stepName = failedPhase,
                reason = "Untrusted host for account 12345678-90ab-cdef-1234-567890abcdef token=secretTokValue99",
                processLogTail = "javac: exit 1"
            )
            assertTrue(details.contains("Step: $failedPhase"))
            assertTrue(details.contains("12345678..."))
            assertFalse(details.contains("secretTokValue99"))

            EngineDataPrepareTelemetry.save(
                filesDir,
                EngineDataPrepareMetrics(
                    status = "failed",
                    minecraftVersion = "26.3",
                    failedPhase = failedPhase,
                    lastError = details,
                    updatedAtMs = 2000L
                )
            )
            val report = RustDiagnosticsCollector.collectRustDiagnosticsFromDirs(
                filesDir = filesDir,
                statusDir = statusDir,
                logcatProvider = { "" }
            )
            assertTrue("Expected failed_phase in Copy report:\n$report", report.contains("failed_phase: $failedPhase"))
            assertTrue("Expected last_error in Copy report:\n$report", report.contains("last_error: Step: $failedPhase"))
            assertFalse(report.contains("secretTokValue99"))
            assertFalse(report.contains("12345678-90ab-cdef-1234-567890abcdef"))

            assertEquals(1024, EngineDataPrepareSupport.recommendJvmHeapMb(8L * 1024 * 1024 * 1024))
            assertEquals(768, EngineDataPrepareSupport.recommendJvmHeapMb(6L * 1024 * 1024 * 1024))
            assertEquals(640, EngineDataPrepareSupport.recommendJvmHeapMb(4L * 1024 * 1024 * 1024))
            assertEquals(512, EngineDataPrepareSupport.recommendJvmHeapMb(3L * 1024 * 1024 * 1024))
        } finally {
            root.deleteRecursively()
        }
    }
}
