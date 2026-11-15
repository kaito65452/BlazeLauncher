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

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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

    @Test
    fun javacVerboseGcFlagsConstantsAndClasspathReductionMatchSpec() {
        assertEquals(15_000L, EngineDataPrepareSupport.DEFAULT_HEARTBEAT_INTERVAL_MS)
        assertEquals(600_000L, EngineDataPrepareSupport.DEFAULT_JAVAC_STEP_TIMEOUT_MS)
        assertEquals(40, EngineDataPrepareSupport.STALL_ERROR_TAIL_LINES)
        assertEquals(60, EngineDataPrepareSupport.COPY_REPORT_LOG_TAIL_LINES)

        val argFile = EngineDataPrepareSupport.formatJavacArgFile(
            classpath = "/tmp/client.jar",
            sourcePath = "/tmp/src",
            outputDir = "/tmp/out",
            javaSourceFiles = listOf(File("/tmp/src/A.java"))
        )
        assertTrue(argFile.contains("-verbose"))

        val javacFlags = EngineDataPrepareSupport.buildJavacJvmFlags(1024)
        assertTrue(javacFlags.contains("-Xlog:gc"))
        assertTrue(javacFlags.contains("-Xmx1024M"))
        assertTrue(javacFlags.contains("-Xms256M"))

        val smallFlags = EngineDataPrepareSupport.buildJavacJvmFlags(192)
        assertTrue(smallFlags.contains("-Xms192M"))
        assertTrue(smallFlags.contains("-Xmx192M"))
        assertEquals(768, EngineDataPrepareSupport.recommendRetryHeapMb(1024))
        assertEquals(384, EngineDataPrepareSupport.recommendRetryHeapMb(512))

        val fullCp = listOf(
            "/cache/versions/26.3/client.jar",
            "/cache/libs/gson-2.11.jar",
            "/cache/libs/joml-1.10.jar",
            "/cache/libs/lwjgl-3.3.3.jar",
            "/cache/libs/lwjgl-opengl-3.3.3.jar",
            "/cache/libs/netty-transport-4.1.jar",
            "/cache/libs/icu4j-73.2.jar",
            "/cache/libs/oshi-core-6.4.jar"
        ).joinToString(":")
        val reduced = EngineDataPrepareSupport.reduceClasspathForRetry(fullCp)
        val reducedList = EngineDataPrepareSupport.splitClasspathEntries(reduced)
        assertEquals(3, reducedList.size)
        assertTrue(reducedList.contains("/cache/versions/26.3/client.jar"))
        assertTrue(reducedList.contains("/cache/libs/gson-2.11.jar"))
        assertTrue(reducedList.contains("/cache/libs/joml-1.10.jar"))
        assertFalse(reduced.contains("lwjgl"))
        assertFalse(reduced.contains("icu4j"))
    }

    @Test
    fun jvmHeartbeatCapturesElapsedStepGcFreeHeapAndJavacState() = runBlocking {
        val clockMs = AtomicLong(1_700_000_000_000L)
        val capturedHeartbeats = mutableListOf<JvmWorkerHeartbeat>()
        val gcLogLines = listOf(
            "[parsing started SimpleFileObject[PrepareWorker.java]]",
            "[0.410s][info][gc] GC(1) Pause Young (Normal) 320M->140M(512M) 5.2ms",
            "[checking dev.horizonlab.parity.VanillaEntityModelExporter]"
        )

        val step = JvmStepSpec(
            name = "Compiling client parity exporters (javac 25)",
            jvmArgs = "${EngineDataPrepareSupport.buildJavacJvmFlags(1024)} -m jdk.compiler/com.sun.tools.javac.Main @client.args",
            isJavac = true,
            timeoutMs = 2_000L,
            initialHeapMb = 1024,
            classpathEntriesCount = 8
        )

        val result = EngineDataPrepareSupport.runJvmStepWithMonitor(
            step = step,
            heartbeatIntervalMs = 20L,
            nowMs = { clockMs.get() },
            runWorker = { _, _, _ ->
                repeat(4) {
                    delay(25L)
                    clockMs.addAndGet(15_000L)
                }
                0
            },
            killWorker = {},
            isWorkerAlive = { true },
            readLogLines = { gcLogLines },
            sampleFallbackFreeHeapMb = { 0L to "fallback" },
            onHeartbeat = { hb, _, _ -> capturedHeartbeats.add(hb) }
        )

        assertTrue("Expected >= 3 heartbeats, got ${capturedHeartbeats.size}", capturedHeartbeats.size >= 3)
        val activeHeartbeats = capturedHeartbeats.dropLast(1)
        assertTrue(activeHeartbeats.all { it.javacRunning })
        assertTrue(activeHeartbeats.all { it.currentStep == "Compiling client parity exporters (javac 25)" })
        assertTrue(activeHeartbeats.all { it.freeHeapMb == 884L && it.freeHeapSource == "jvm-gc-log" })
        assertTrue(capturedHeartbeats.any { it.elapsedSeconds >= 15L })
        assertFalse(capturedHeartbeats.last().javacRunning)
        assertEquals(1, result.attempts.size)
        assertEquals("success", result.attempts.first().outcome)
    }

    @Test
    fun copyReportShowsPhaseStartTimeHeartbeatAgeAndOnlyLast60WorkerLogLines() {
        val root = Files.createTempDirectory("blaze-prepare-copy-report-60").toFile()
        try {
            val filesDir = File(root, "files").apply { mkdirs() }
            val statusDir = File(root, "status").apply { mkdirs() }
            val logFile = File(root, "latest_process.log")
            val allLines = (1..150).map { i ->
                when (i) {
                    120 -> "worker_log_line_${"%03d".format(i)} [gc] Pause Young 200M->80M(512M) access_token=topSecretWorker1"
                    135 -> "worker_log_line_${"%03d".format(i)} player 12345678-90ab-cdef-1234-567890abcdef"
                    else -> "worker_log_line_${"%03d".format(i)} [loading java/lang/Object.class]"
                }
            }
            logFile.writeText(allLines.joinToString("\n") + "\n")

            val phaseStart = 1_700_000_000_000L
            val hbTime = 1_700_000_045_000L
            val reportTime = 1_700_000_057_000L
            EngineDataPrepareTelemetry.save(
                filesDir,
                EngineDataPrepareMetrics(
                    status = "running",
                    minecraftVersion = "26.3",
                    currentPhase = "jvm_exporters: Compiling client parity exporters (javac 25)",
                    phaseStartMs = phaseStart,
                    lastHeartbeatAtMs = hbTime,
                    heartbeat = JvmWorkerHeartbeat(
                        elapsedSeconds = 45L,
                        currentStep = "Compiling client parity exporters (javac 25)",
                        freeHeapMb = 432L,
                        freeHeapSource = "jvm-gc-log",
                        javacRunning = true,
                        attempt = 1,
                        recordedAtMs = hbTime
                    ),
                    updatedAtMs = hbTime
                )
            )

            val report = RustDiagnosticsCollector.collectRustDiagnosticsFromDirs(
                filesDir = filesDir,
                statusDir = statusDir,
                logcatProvider = { "" },
                jvmWorkerLogFile = logFile,
                nowMs = reportTime
            )

            assertTrue(report.contains("current_phase: jvm_exporters: Compiling client parity exporters (javac 25)"))
            assertTrue(report.contains("phase_start_time:") && !report.contains("phase_start_time: unavailable"))
            assertTrue(report.contains("last_heartbeat_age: 12s ago"))
            assertTrue(report.contains("free_heap=432MB[jvm-gc-log]"))
            assertTrue(report.contains("javac_running=true"))

            assertFalse("Older line 001 must stay out of Copy report", report.contains("worker_log_line_001"))
            assertFalse("Older line 090 must stay out of Copy report", report.contains("worker_log_line_090"))
            assertTrue("Line 091 (60th from end) must appear in Copy report", report.contains("worker_log_line_091"))
            assertTrue("Line 150 must appear in Copy report", report.contains("worker_log_line_150"))
            assertFalse(report.contains("topSecretWorker1"))
            assertFalse(report.contains("12345678-90ab-cdef-1234-567890abcdef"))
            assertTrue(report.contains("12345678..."))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun javacTimeoutKillsBothAttemptsReportsLast40RedactedLinesAndAttemptHistory() = runBlocking {
        val kills = AtomicInteger(0)
        val launchedArgs = mutableListOf<String>()
        val clockMs = AtomicLong(1_700_000_000_000L)
        val logLines = (1..90).map { i ->
            if (i == 85) "javac_line_${"%03d".format(i)} token=secretStall42"
            else "javac_line_${"%03d".format(i)} [parsing VanillaMobDataExporter.java]"
        }
        val fullCp = "/mc/client.jar:/libs/gson.jar:/libs/lwjgl.jar:/libs/netty-transport.jar:/libs/icu4j.jar"
        val step = JvmStepSpec(
            name = "Compiling client parity exporters (javac 25)",
            jvmArgs = "${EngineDataPrepareSupport.buildJavacJvmFlags(1024)} -m jdk.compiler/com.sun.tools.javac.Main @client.args",
            isJavac = true,
            timeoutMs = 80L,
            initialHeapMb = 1024,
            classpathEntriesCount = 5,
            retryOnTimeout = JvmStepRetryConfig(
                originalClasspath = fullCp,
                initialHeapMb = 1024,
                buildRetryJvmArgs = { reducedCp, loweredHeap ->
                    "${EngineDataPrepareSupport.buildJavacJvmFlags(loweredHeap)} cp_count=${EngineDataPrepareSupport.countClasspathEntries(reducedCp)} cp=$reducedCp"
                }
            )
        )

        try {
            EngineDataPrepareSupport.runJvmStepWithMonitor(
                step = step,
                heartbeatIntervalMs = 20L,
                nowMs = { clockMs.addAndGet(20_000L) },
                runWorker = { _, args, _ ->
                    launchedArgs.add(args)
                    delay(5_000L)
                    0
                },
                killWorker = { kills.incrementAndGet() },
                isWorkerAlive = { true },
                readLogLines = { logLines },
                sampleFallbackFreeHeapMb = { 200L to "proc-status" }
            )
            fail("Expected EngineDataPrepareException after 2 timed-out attempts")
        } catch (e: EngineDataPrepareException) {
            assertEquals(2, kills.get())
            assertEquals(2, launchedArgs.size)
            assertTrue(launchedArgs[0].contains("-Xmx1024M"))
            assertTrue(launchedArgs[1].contains("-Xmx768M"))
            assertTrue(launchedArgs[1].contains("cp_count=2"))
            assertFalse(launchedArgs[1].contains("lwjgl"))

            assertTrue(e.redactedMessage.contains("javac stalled after "))
            assertTrue(e.redactedMessage.contains(" s"))
            assertTrue(e.redactedMessage.contains("2 attempts"))
            assertTrue(e.redactedDetails.contains("Attempt #1: outcome=timeout"))
            assertTrue(e.redactedDetails.contains("Attempt #2: outcome=timeout"))
            assertFalse("Line 050 must be excluded from 40-line stall tail", e.redactedDetails.contains("javac_line_050"))
            assertTrue("Line 051 (40th from end) must be included", e.redactedDetails.contains("javac_line_051"))
            assertTrue("Line 090 must be included", e.redactedDetails.contains("javac_line_090"))
            assertFalse(e.redactedDetails.contains("secretStall42"))
        }
    }

    @Test
    fun javacTimeoutRetriesOnceWithSmallerClasspathAndLoweredXmxAndReportsBothAttempts() = runBlocking {
        val root = Files.createTempDirectory("blaze-prepare-retry-success").toFile()
        try {
            val filesDir = File(root, "files").apply { mkdirs() }
            val statusDir = File(root, "status").apply { mkdirs() }
            val kills = AtomicInteger(0)
            val attemptArgs = mutableListOf<String>()
            val clockMs = AtomicLong(1_700_000_000_000L)

            val fullCp = listOf(
                "/mc/client.jar",
                "/libs/gson.jar",
                "/libs/joml.jar",
                "/libs/lwjgl.jar",
                "/libs/netty-transport.jar",
                "/libs/icu4j.jar",
                "/libs/oshi-core.jar"
            ).joinToString(":")

            val step = JvmStepSpec(
                name = "Compiling client parity exporters (javac 25)",
                jvmArgs = "${EngineDataPrepareSupport.buildJavacJvmFlags(1024)} cp=$fullCp",
                isJavac = true,
                timeoutMs = 80L,
                initialHeapMb = 1024,
                classpathEntriesCount = 7,
                retryOnTimeout = JvmStepRetryConfig(
                    originalClasspath = fullCp,
                    initialHeapMb = 1024,
                    buildRetryJvmArgs = { reducedCp, loweredHeap ->
                        "${EngineDataPrepareSupport.buildJavacJvmFlags(loweredHeap)} cp=$reducedCp"
                    }
                )
            )

            val result = EngineDataPrepareSupport.runJvmStepWithMonitor(
                step = step,
                heartbeatIntervalMs = 20L,
                nowMs = { clockMs.addAndGet(5_000L) },
                runWorker = { attempt, args, _ ->
                    attemptArgs.add(args)
                    if (attempt == 1) {
                        delay(5_000L)
                        1
                    } else {
                        delay(25L)
                        0
                    }
                },
                killWorker = { kills.incrementAndGet() },
                isWorkerAlive = { true },
                readLogLines = { listOf("[0.200s][info][gc] GC(0) Pause Young 180M->90M(256M) 2ms") },
                sampleFallbackFreeHeapMb = { 500L to "proc-status" }
            )

            assertEquals(1, kills.get())
            assertEquals(2, result.attempts.size)
            assertEquals("timeout", result.attempts[0].outcome)
            assertEquals(1024, result.attempts[0].heapMb)
            assertEquals(7, result.attempts[0].classpathEntries)
            assertEquals("success", result.attempts[1].outcome)
            assertEquals(768, result.attempts[1].heapMb)
            assertEquals(3, result.attempts[1].classpathEntries)
            assertTrue(attemptArgs[1].contains("-Xmx768M"))
            assertFalse(attemptArgs[1].contains("lwjgl"))
            assertEquals(1, result.warnings.size)
            assertTrue(result.warnings.first().contains("attempt 1 timed out"))
            assertTrue(result.warnings.first().contains("-Xmx768M"))

            EngineDataPrepareTelemetry.save(
                filesDir,
                EngineDataPrepareMetrics(
                    status = "ready",
                    minecraftVersion = "26.3",
                    currentPhase = "ready",
                    phaseStartMs = 1_700_000_000_000L,
                    lastHeartbeatAtMs = result.lastHeartbeat?.recordedAtMs ?: 0L,
                    heartbeat = result.lastHeartbeat,
                    stepAttempts = result.attempts,
                    warnings = result.warnings,
                    workerLogTail = result.logTail60
                )
            )
            val report = RustDiagnosticsCollector.collectRustDiagnosticsFromDirs(
                filesDir = filesDir,
                statusDir = statusDir,
                logcatProvider = { "" },
                nowMs = (result.lastHeartbeat?.recordedAtMs ?: 0L) + 5_000L
            )
            assertTrue(report.contains("attempt #1 [Compiling client parity exporters (javac 25)]: outcome=timeout"))
            assertTrue(report.contains("attempt #2 [Compiling client parity exporters (javac 25)]: outcome=success"))
        } finally {
            root.deleteRecursively()
        }
    }
}
