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

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Process
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.components.InstallableItem
import com.movtery.zalithlauncher.components.jre.Jre
import com.movtery.zalithlauncher.components.jre.UnpackJreTask
import com.movtery.zalithlauncher.context.GlobalContext
import com.movtery.zalithlauncher.game.download.jvm_server.JvmService
import com.movtery.zalithlauncher.game.download.jvm_server.LATEST_PROCESS_LOG_FILE
import com.movtery.zalithlauncher.game.download.jvm_server.listBlockingProcesses
import com.movtery.zalithlauncher.game.download.jvm_server.startJvmServiceAndWaitExit
import com.movtery.zalithlauncher.game.download.jvm_server.stopAllNonMainProcesses
import com.movtery.zalithlauncher.game.multirt.RuntimesManager
import com.movtery.zalithlauncher.game.path.getGameHome
import com.movtery.zalithlauncher.notification.NoticeProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

data class PrepareUiState(
    val running: Boolean = false,
    val phase: String = "",
    val detail: String = "",
    val progressFraction: Float? = null,
    val peakRssKb: Long = 0L,
    val prepared: Boolean = false,
    val completedSummary: String? = null,
    val redactedError: String? = null,
    val warnings: List<String> = emptyList()
)

class EngineDataPrepareException(
    val redactedMessage: String,
    val redactedDetails: String,
    val failedPhase: String = "prepare"
) : RuntimeException(redactedMessage)

internal data class JvmStepRetryConfig(
    val originalClasspath: String,
    val initialHeapMb: Int,
    val buildRetryJvmArgs: (reducedClasspath: String, loweredHeapMb: Int) -> String
)

internal data class JvmStepSpec(
    val name: String,
    val jvmArgs: String,
    val isJavac: Boolean = false,
    val timeoutMs: Long = if (isJavac) {
        EngineDataPrepareSupport.DEFAULT_JAVAC_STEP_TIMEOUT_MS
    } else {
        EngineDataPrepareSupport.DEFAULT_EXPORTER_STEP_TIMEOUT_MS
    },
    val initialHeapMb: Int = 512,
    val classpathEntriesCount: Int = 0,
    val retryOnTimeout: JvmStepRetryConfig? = null
)

internal data class JvmStepRunResult(
    val attempts: List<JvmStepAttemptRecord>,
    val lastHeartbeat: JvmWorkerHeartbeat?,
    val warnings: List<String>,
    val logTail60: List<String>
)

/**
 * Pure JVM helpers split out for deterministic unit testing without Android framework stubs.
 */
internal object EngineDataPrepareSupport {
    const val LOCK_ASSET_NAME = "research-lock.json"
    const val CONTROLS_ASSET_NAME = "bedrock-controls.json"
    const val SHIM_ASSET_PATH = "blaze-prepare/PrepareBatchShim.java"
    const val PARITY_ASSET_SUBDIR = "horizon-parity/dev/horizonlab/parity"
    const val TARGET_MINECRAFT_VERSION = "26.3"

    const val DEFAULT_HEARTBEAT_INTERVAL_MS: Long = 15_000L
    const val DEFAULT_JAVAC_STEP_TIMEOUT_MS: Long = 10L * 60L * 1000L
    const val DEFAULT_EXPORTER_STEP_TIMEOUT_MS: Long = 12L * 60L * 1000L
    const val STALL_ERROR_TAIL_LINES: Int = 40
    const val COPY_REPORT_LOG_TAIL_LINES: Int = 60
    const val GC_LOG_SCAN_LINES: Int = 400

    private val GC_HEAP_REGEX = Regex("""(\d+)([KMG])->(\d+)([KMG])\((\d+)([KMG])\)""")

    private val RETRY_ESSENTIAL_LIB_TOKENS = listOf(
        "gson",
        "datafixerupper",
        "brigadier",
        "joml",
        "guava",
        "fastutil",
        "slf4j",
        "log4j-api",
        "commons-lang3",
        "commons-io",
        "annotations",
        "jsr305",
        "failureaccess",
        "netty-buffer",
        "netty-common",
        "authlib"
    )

    private val RETRY_HEAVY_EXCLUDED_TOKENS = listOf(
        "lwjgl",
        "icu4j",
        "oshi",
        "jna",
        "text2speech",
        "jorbis",
        "paulscode",
        "jlayer",
        "tracy",
        "httpclient",
        "httpcore",
        "commons-compress",
        "commons-codec",
        "commons-logging",
        "patchy",
        "netty-transport",
        "netty-codec",
        "netty-handler",
        "netty-resolver"
    )

    val SERVER_EXPORTER_FILES: List<String> = listOf(
        "ParityData.java",
        "PrepareWorker.java",
        "PrepareBatchShim.java",
        "VanillaBlockBehaviourExporter.java",
        "VanillaBlockLandingOracle.java",
        "VanillaBlockPropertiesOracle.java",
        "VanillaBlockStateCodecOracle.java",
        "VanillaBlockSupportOracle.java",
        "VanillaBlockSurvivalOracle.java",
        "VanillaCollisionOracle.java",
        "VanillaCommandParseOracle.java",
        "VanillaCreativeTabsExporter.java",
        "VanillaDamageSourceOracle.java",
        "VanillaEntityAttributeOracle.java",
        "VanillaEntityTypesExporter.java",
        "VanillaFaceVisibilityOracle.java",
        "VanillaGameplayFactsExporter.java",
        "VanillaHeightmapOracle.java",
        "VanillaMovementRegistryOracle.java",
        "VanillaPlacementOracle.java",
        "VanillaPlayerHealthOracle.java",
        "VanillaSimpleBlockOracle.java",
        "VanillaStructureBlockExporter.java"
    )

    val CLIENT_EXPORTER_FILES: List<String> = listOf(
        "PrepareWorker.java",
        "PrepareBatchShim.java",
        "VanillaBlockStateBindingOracle.java",
        "VanillaCuboidModelOracle.java",
        "VanillaEntityModelExporter.java",
        "VanillaMobDataExporter.java"
    )

    fun recommendJvmHeapMb(totalDeviceRamBytes: Long?): Int {
        if (totalDeviceRamBytes == null || totalDeviceRamBytes <= 0L) return 512
        val totalMb = totalDeviceRamBytes / (1024L * 1024L)
        return when {
            totalMb >= 8 * 1024 -> 1024
            totalMb >= 6 * 1024 -> 768
            totalMb >= 4 * 1024 -> 640
            else -> 512
        }
    }

    fun recommendRetryHeapMb(initialHeapMb: Int): Int {
        if (initialHeapMb <= 384) return maxOf(256, initialHeapMb - 64)
        return (initialHeapMb * 3 / 4).coerceIn(384, initialHeapMb - 64)
    }

    fun splitClasspathEntries(classpath: String): List<String> = classpath
        .split(':')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    fun countClasspathEntries(classpath: String): Int = splitClasspathEntries(classpath).size

    /**
     * Trims bulky runtime/native/network JARs (LWJGL, Netty transport, ICU4J, Oshi, JNA, etc.)
     * while keeping the primary Minecraft game JAR and compile-time signature libraries.
     */
    fun reduceClasspathForRetry(fullClasspath: String): String {
        val entries = splitClasspathEntries(fullClasspath)
        if (entries.size <= 1) return entries.joinToString(":")
        val primary = entries.first()
        val kept = mutableListOf(primary)
        for (entry in entries.drop(1)) {
            val fileName = File(entry).name.lowercase()
            if (RETRY_HEAVY_EXCLUDED_TOKENS.any { fileName.contains(it) }) {
                continue
            }
            if (!entry.endsWith(".jar", ignoreCase = true) ||
                RETRY_ESSENTIAL_LIB_TOKENS.any { fileName.contains(it) }
            ) {
                kept.add(entry)
            }
        }
        val finalEntries = if (kept.size < entries.size) {
            kept
        } else {
            entries.take(maxOf(1, entries.size / 2))
        }
        return finalEntries.joinToString(":")
    }

    fun quoteCmdArg(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    fun formatJavacArgFile(
        classpath: String,
        sourcePath: String,
        outputDir: String,
        javaSourceFiles: List<File>,
        verbose: Boolean = true
    ): String = buildString {
        appendLine("--release")
        appendLine("25")
        appendLine("-proc:none")
        if (verbose) {
            appendLine("-verbose")
        }
        appendLine("-encoding")
        appendLine("UTF-8")
        appendLine("-cp")
        appendLine(quoteCmdArg(classpath))
        appendLine("-sourcepath")
        appendLine(quoteCmdArg(sourcePath))
        appendLine("-d")
        appendLine(quoteCmdArg(outputDir))
        javaSourceFiles.forEach { file ->
            appendLine(quoteCmdArg(file.absolutePath))
        }
    }

    fun buildCommonJvmFlags(heapMb: Int, includeGcLog: Boolean = false): String {
        val xmsMb = minOf(256, heapMb).coerceAtLeast(64)
        val flags = mutableListOf(
            "-Djava.awt.headless=true",
            "-Xms${xmsMb}M",
            "-Xmx${heapMb}M",
            "-XX:TieredStopAtLevel=1",
            "-XX:+UseSerialGC"
        )
        if (includeGcLog) {
            flags.add("-Xlog:gc")
        }
        return flags.joinToString(" ")
    }

    fun buildJavacJvmFlags(heapMb: Int): String = buildCommonJvmFlags(heapMb, includeGcLog = true)

    fun readTailLines(file: File, maxLines: Int, maxBytes: Long = 131_072L): List<String> {
        if (!file.isFile || maxLines <= 0) return emptyList()
        return runCatching {
            val len = file.length()
            if (len <= 0L) return@runCatching emptyList()
            val start = maxOf(0L, len - maxBytes)
            val toRead = (len - start).toInt()
            val bytes = ByteArray(toRead)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                raf.readFully(bytes)
            }
            var text = String(bytes, Charsets.UTF_8)
            if (start > 0L) {
                val firstNl = text.indexOf('\n')
                text = if (firstNl >= 0 && firstNl + 1 < text.length) text.substring(firstNl + 1) else ""
            }
            text.lineSequence()
                .map { it.removeSuffix("\r") }
                .filter { it.isNotEmpty() }
                .toList()
                .takeLast(maxLines)
        }.getOrDefault(emptyList())
    }

    private fun toMb(value: Long, unit: String): Long = when (unit.uppercase()) {
        "K" -> value / 1024L
        "G" -> value * 1024L
        else -> value
    }

    fun parseLatestGcFreeHeapMb(logLines: List<String>, configuredMaxHeapMb: Int = 0): Pair<Long, String>? {
        for (line in logLines.asReversed()) {
            val match = GC_HEAP_REGEX.find(line) ?: continue
            val afterMb = toMb(match.groupValues[3].toLongOrNull() ?: continue, match.groupValues[4])
            val committedMb = toMb(match.groupValues[5].toLongOrNull() ?: continue, match.groupValues[6])
            val ceilingMb = if (configuredMaxHeapMb > 0) maxOf(configuredMaxHeapMb.toLong(), committedMb) else committedMb
            val freeMb = maxOf(0L, ceilingMb - afterMb)
            return freeMb to "jvm-gc-log"
        }
        return null
    }

    fun formatAttemptSummary(attempts: List<JvmStepAttemptRecord>): String = attempts.joinToString("\n") { rec ->
        val detailSuffix = if (rec.detail.isNotBlank()) " — ${rec.detail}" else ""
        "Attempt #${rec.attempt}: outcome=${rec.outcome}, elapsed=${rec.elapsedSeconds}s, xmx=${rec.heapMb}M, cp=${rec.classpathEntries}$detailSuffix"
    }

    fun buildRedactedErrorDetails(
        stepName: String,
        reason: String,
        processLogTail: String = ""
    ): String = buildString {
        appendLine("Step: ${RustDiagnosticsCollector.redactDiagnosticText(stepName)}")
        appendLine("Error: ${RustDiagnosticsCollector.redactDiagnosticText(reason)}")
        if (processLogTail.isNotBlank()) {
            appendLine()
            appendLine("--- :jvm log tail (redacted) ---")
            appendLine(RustDiagnosticsCollector.redactDiagnosticText(processLogTail.trim()))
        }
    }.trimEnd()

    internal suspend fun runJvmStepWithMonitor(
        step: JvmStepSpec,
        heartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS,
        nowMs: () -> Long = System::currentTimeMillis,
        runWorker: suspend (attempt: Int, jvmArgs: String, stepTitle: String) -> Int,
        killWorker: suspend () -> Unit,
        isWorkerAlive: () -> Boolean,
        readLogLines: () -> List<String>,
        sampleFallbackFreeHeapMb: (configuredHeapMb: Int) -> Pair<Long, String>,
        onHeartbeat: suspend (heartbeat: JvmWorkerHeartbeat, redactedLogTail60: List<String>, attemptsSoFar: List<JvmStepAttemptRecord>) -> Unit = { _, _, _ -> },
        onRetryNotice: suspend (message: String, reducedCpEntries: Int, loweredHeapMb: Int) -> Unit = { _, _, _ -> }
    ): JvmStepRunResult {
        val attempts = mutableListOf<JvmStepAttemptRecord>()
        val warnings = mutableListOf<String>()
        var currentArgs = step.jvmArgs
        var currentHeapMb = step.initialHeapMb
        var currentCpEntries = step.classpathEntriesCount
        var stepTitle = step.name
        var lastHeartbeat: JvmWorkerHeartbeat? = null
        var latestLogTail60: List<String> = emptyList()
        val maxAttempts = if (step.retryOnTimeout != null) 2 else 1

        for (attemptNum in 1..maxAttempts) {
            val attemptStartMs = nowMs()

            suspend fun emitHeartbeat(javacAliveOverride: Boolean? = null) {
                val rawLines = readLogLines()
                latestLogTail60 = rawLines
                    .takeLast(COPY_REPORT_LOG_TAIL_LINES)
                    .map { RustDiagnosticsCollector.redactDiagnosticText(it) }
                val (freeHeapMb, freeHeapSource) = parseLatestGcFreeHeapMb(
                    rawLines.takeLast(GC_LOG_SCAN_LINES),
                    currentHeapMb
                ) ?: sampleFallbackFreeHeapMb(currentHeapMb)
                val hb = JvmWorkerHeartbeat(
                    elapsedSeconds = maxOf(0L, (nowMs() - attemptStartMs) / 1000L),
                    currentStep = stepTitle,
                    freeHeapMb = maxOf(0L, freeHeapMb),
                    freeHeapSource = freeHeapSource,
                    javacRunning = javacAliveOverride ?: (step.isJavac && isWorkerAlive()),
                    attempt = attemptNum,
                    recordedAtMs = nowMs()
                )
                lastHeartbeat = hb
                onHeartbeat(hb, latestLogTail60, attempts.toList())
            }

            val exitCode: Int? = coroutineScope {
                val heartbeatJob = launch {
                    emitHeartbeat()
                    while (isActive) {
                        delay(heartbeatIntervalMs)
                        emitHeartbeat()
                    }
                }
                try {
                    withTimeoutOrNull(step.timeoutMs) {
                        runWorker(attemptNum, currentArgs, stepTitle)
                    }
                } finally {
                    heartbeatJob.cancelAndJoin()
                }
            }
            currentCoroutineContext().ensureActive()

            if (exitCode == 0) {
                val elapsedSec = maxOf(0L, (nowMs() - attemptStartMs) / 1000L)
                attempts.add(
                    JvmStepAttemptRecord(
                        stepName = step.name,
                        attempt = attemptNum,
                        outcome = "success",
                        elapsedSeconds = elapsedSec,
                        heapMb = currentHeapMb,
                        classpathEntries = currentCpEntries
                    )
                )
                emitHeartbeat(javacAliveOverride = false)
                if (attemptNum == 2 && attempts.size == 2) {
                    warnings.add(
                        "${step.name} attempt 1 timed out (${attempts[0].detail}, -Xmx${attempts[0].heapMb}M, cp=${attempts[0].classpathEntries}); " +
                            "retry attempt 2 succeeded in ${elapsedSec}s with smaller classpath ($currentCpEntries entries) and lowered -Xmx${currentHeapMb}M"
                    )
                }
                return JvmStepRunResult(
                    attempts = attempts.toList(),
                    lastHeartbeat = lastHeartbeat,
                    warnings = warnings.toList(),
                    logTail60 = latestLogTail60
                )
            }

            if (exitCode == null) {
                killWorker()
                val elapsedSec = maxOf(1L, (nowMs() - attemptStartMs) / 1000L)
                val rawLines = readLogLines()
                latestLogTail60 = rawLines
                    .takeLast(COPY_REPORT_LOG_TAIL_LINES)
                    .map { RustDiagnosticsCollector.redactDiagnosticText(it) }
                val stallHeadline = if (step.isJavac) {
                    "javac stalled after $elapsedSec s"
                } else {
                    "${step.name} stalled after $elapsedSec s"
                }
                attempts.add(
                    JvmStepAttemptRecord(
                        stepName = step.name,
                        attempt = attemptNum,
                        outcome = "timeout",
                        elapsedSeconds = elapsedSec,
                        heapMb = currentHeapMb,
                        classpathEntries = currentCpEntries,
                        detail = stallHeadline
                    )
                )
                emitHeartbeat(javacAliveOverride = false)

                val retryCfg = step.retryOnTimeout
                if (attemptNum == 1 && retryCfg != null) {
                    val reducedCp = reduceClasspathForRetry(retryCfg.originalClasspath)
                    val loweredHeapMb = recommendRetryHeapMb(retryCfg.initialHeapMb)
                    val reducedCount = countClasspathEntries(reducedCp)
                    onRetryNotice(
                        "$stallHeadline; retrying once with smaller classpath ($reducedCount entries) and -Xmx${loweredHeapMb}M",
                        reducedCount,
                        loweredHeapMb
                    )
                    currentCpEntries = reducedCount
                    currentHeapMb = loweredHeapMb
                    currentArgs = retryCfg.buildRetryJvmArgs(reducedCp, loweredHeapMb)
                    stepTitle = "${step.name} (retry 2 -Xmx${loweredHeapMb}M)"
                    continue
                }

                val tail40 = rawLines.takeLast(STALL_ERROR_TAIL_LINES).joinToString("\n")
                val reasonText = if (attempts.size > 1) {
                    "$stallHeadline (${step.name})\n${formatAttemptSummary(attempts)}"
                } else {
                    "$stallHeadline (${step.name})"
                }
                val details = buildRedactedErrorDetails(
                    stepName = step.name,
                    reason = reasonText,
                    processLogTail = tail40
                )
                val msg = if (attempts.size > 1) {
                    "$stallHeadline after 2 attempts (${step.name})"
                } else {
                    "$stallHeadline (${step.name})"
                }
                throw EngineDataPrepareException(
                    redactedMessage = msg,
                    redactedDetails = details,
                    failedPhase = "jvm_exporters: ${step.name}"
                )
            }

            // Non-zero exit code
            val elapsedSec = maxOf(0L, (nowMs() - attemptStartMs) / 1000L)
            val rawLines = readLogLines()
            latestLogTail60 = rawLines
                .takeLast(COPY_REPORT_LOG_TAIL_LINES)
                .map { RustDiagnosticsCollector.redactDiagnosticText(it) }
            attempts.add(
                JvmStepAttemptRecord(
                    stepName = step.name,
                    attempt = attemptNum,
                    outcome = "failed (exit=$exitCode)",
                    elapsedSeconds = elapsedSec,
                    heapMb = currentHeapMb,
                    classpathEntries = currentCpEntries,
                    detail = ":jvm process exited with code $exitCode"
                )
            )
            emitHeartbeat(javacAliveOverride = false)

            val tail40 = rawLines.takeLast(STALL_ERROR_TAIL_LINES).joinToString("\n")
            val reasonText = if (attempts.size > 1) {
                ":jvm process exited with code $exitCode\n${formatAttemptSummary(attempts)}"
            } else {
                ":jvm process exited with code $exitCode"
            }
            val details = buildRedactedErrorDetails(
                stepName = step.name,
                reason = reasonText,
                processLogTail = tail40
            )
            val msg = if (attempts.size > 1) {
                "${step.name} failed on attempt 2 (exit=$exitCode) after attempt 1 timeout"
            } else {
                "${step.name} failed (exit=$exitCode)"
            }
            throw EngineDataPrepareException(
                redactedMessage = msg,
                redactedDetails = details,
                failedPhase = "jvm_exporters: ${step.name}"
            )
        }

        throw IllegalStateException("Unreachable JvmStep attempt state for ${step.name}")
    }
}

/**
 * Orchestrates on-device `26.3` engine-data preparation across `libblaze_prepare.so` (Rust)
 * and OpenJDK 25 `:jvm` (`Internal-25`) without bundling any Mojang assets or Python binaries.
 */
object EngineDataPreparer {
    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeFlag = AtomicBoolean(false)

    @Volatile
    private var activeJob: Job? = null

    private val _uiState = MutableStateFlow(PrepareUiState())
    val uiState: StateFlow<PrepareUiState> = _uiState.asStateFlow()

    fun refreshPreparedState(context: Context) {
        val prepared = runCatching {
            if (BlazePrepareNative.ensureLoaded()) {
                BlazePrepareNative.nativeIsPrepared(
                    context.filesDir.absolutePath,
                    EngineDataPrepareSupport.TARGET_MINECRAFT_VERSION
                )
            } else {
                isPreparedFallback(context.filesDir)
            }
        }.getOrElse { isPreparedFallback(context.filesDir) }

        val metrics = EngineDataPrepareTelemetry.load(context.filesDir)
        val persistedFailure = if (!prepared && metrics?.status == "failed" && metrics.lastError.isNotBlank()) {
            RustDiagnosticsCollector.redactDiagnosticText(metrics.lastError)
        } else {
            null
        }
        @Suppress("USELESS_ELVIS")
        val persistedFailedPhase = (metrics?.failedPhase ?: "").trim()
        _uiState.value = _uiState.value.copy(
            prepared = prepared,
            phase = if (persistedFailure != null && !_uiState.value.running) {
                context.getString(R.string.developer_prepare_failed)
            } else {
                _uiState.value.phase
            },
            detail = if (persistedFailure != null && _uiState.value.detail.isBlank()) {
                persistedFailedPhase
            } else {
                _uiState.value.detail
            },
            peakRssKb = metrics?.peakRssKb ?: _uiState.value.peakRssKb,
            redactedError = persistedFailure ?: _uiState.value.redactedError,
            warnings = metrics?.safeWarnings() ?: _uiState.value.warnings
        )
    }

    fun startInBackground(context: Context) {
        val appContext = context.applicationContext
        if (!activeFlag.compareAndSet(false, true)) return

        activeJob = coordinatorScope.launch {
            try {
                runPreparation(appContext)
            } catch (_: CancellationException) {
                recordCancelled(appContext)
            } catch (e: EngineDataPrepareException) {
                recordPrepareFailure(appContext, e)
            } catch (t: Throwable) {
                recordUnexpectedFailure(appContext, t)
            } finally {
                activeFlag.set(false)
                activeJob = null
            }
        }
    }

    /**
     * Runs engine-data preparation sequentially inside a [GameInstaller] or [GameLaunchFlow] task.
     */
    suspend fun prepareFromInstaller(
        context: Context,
        onStatusUpdate: ((phase: String, fraction: Float?) -> Unit)? = null
    ): Phase2OutputJson {
        val appContext = context.applicationContext
        check(activeFlag.compareAndSet(false, true)) {
            "Engine data preparation is already in progress."
        }
        return try {
            runPreparation(appContext, onStatusUpdate)
        } catch (ce: CancellationException) {
            runCatching {
                if (BlazePrepareNative.ensureLoaded()) {
                    BlazePrepareNative.nativeRequestCancel()
                }
            }
            recordCancelled(appContext)
            throw ce
        } catch (e: EngineDataPrepareException) {
            recordPrepareFailure(appContext, e)
            throw e
        } catch (t: Throwable) {
            recordUnexpectedFailure(appContext, t)
            throw t
        } finally {
            activeFlag.set(false)
        }
    }

    private fun recordCancelled(appContext: Context) {
        val cancelledMetrics = EngineDataPrepareMetrics(
            status = "cancelled",
            minecraftVersion = EngineDataPrepareSupport.TARGET_MINECRAFT_VERSION,
            updatedAtMs = System.currentTimeMillis()
        )
        EngineDataPrepareTelemetry.save(appContext.filesDir, cancelledMetrics)
        _uiState.value = PrepareUiState(
            running = false,
            phase = appContext.getString(R.string.developer_prepare_cancelled),
            prepared = isPreparedFallback(appContext.filesDir)
        )
    }

    private fun findJvmWorkerPid(context: Context): Int? = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return@runCatching null
        val jvmProcName = "${context.packageName}:jvm"
        am.runningAppProcesses?.firstOrNull { it.processName == jvmProcName }?.pid
    }.getOrNull()

    private suspend fun killJvmWorkerProcess(context: Context) {
        runCatching {
            GlobalContext.stopService(Intent(GlobalContext, JvmService::class.java))
        }
        runCatching { stopAllNonMainProcesses(GlobalContext) }
        runCatching {
            findJvmWorkerPid(context)?.let { pid -> Process.killProcess(pid) }
        }
        for (i in 0 until 20) {
            if (listBlockingProcesses(GlobalContext).isEmpty()) break
            delay(150L)
        }
    }

    private fun readProcVmRssMb(pid: Int): Long? = runCatching {
        val statusFile = File("/proc/$pid/status")
        if (!statusFile.isFile) return@runCatching null
        val line = statusFile.useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
        } ?: return@runCatching null
        val kb = Regex("""\d+""").find(line)?.value?.toLongOrNull() ?: return@runCatching null
        kb / 1024L
    }.getOrNull()

    private fun sampleJvmFallbackFreeHeapMb(context: Context, configuredHeapMb: Int): Pair<Long, String> {
        val jvmPid = findJvmWorkerPid(context)
        if (jvmPid != null) {
            val rssMb = readProcVmRssMb(jvmPid)
            if (rssMb != null) {
                return maxOf(0L, configuredHeapMb.toLong() - rssMb) to "proc-status"
            }
        }
        val availMemMb = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return@runCatching null
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.availMem / (1024L * 1024L)
        }.getOrNull()
        if (availMemMb != null) {
            return availMemMb to "android-avail-mem"
        }
        val rt = Runtime.getRuntime()
        val mainFreeMb = maxOf(0L, (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / (1024L * 1024L))
        return mainFreeMb to "main-runtime-fallback"
    }

    private fun saveFailureTelemetry(
        appContext: Context,
        failedPhase: String,
        redactedDetails: String,
        timings: Map<String, Long> = emptyMap(),
        peakRssKb: Long = 0L,
        warnings: List<String> = emptyList(),
        currentPhase: String? = null,
        phaseStartMs: Long? = null,
        lastHeartbeatAtMs: Long? = null,
        heartbeat: JvmWorkerHeartbeat? = null,
        stepAttempts: List<JvmStepAttemptRecord>? = null,
        workerLogTail: List<String>? = null
    ) {
        val existing = EngineDataPrepareTelemetry.load(appContext.filesDir)
        val status = if (redactedDetails.contains("cancel", ignoreCase = true) ||
            failedPhase.contains("cancel", ignoreCase = true)
        ) {
            "cancelled"
        } else {
            "failed"
        }
        EngineDataPrepareTelemetry.save(
            appContext.filesDir,
            EngineDataPrepareMetrics(
                status = status,
                minecraftVersion = EngineDataPrepareSupport.TARGET_MINECRAFT_VERSION,
                phaseTimingsMs = timings,
                peakRssKb = peakRssKb,
                failedPhase = failedPhase,
                lastError = redactedDetails,
                warnings = warnings,
                updatedAtMs = System.currentTimeMillis(),
                currentPhase = currentPhase ?: existing?.currentPhase.orEmpty().ifBlank { failedPhase },
                phaseStartMs = phaseStartMs ?: (existing?.phaseStartMs ?: 0L),
                lastHeartbeatAtMs = lastHeartbeatAtMs ?: (existing?.lastHeartbeatAtMs ?: 0L),
                heartbeat = heartbeat ?: existing?.heartbeat,
                stepAttempts = stepAttempts ?: existing?.safeStepAttempts().orEmpty(),
                workerLogTail = workerLogTail ?: existing?.safeWorkerLogTail().orEmpty()
            )
        )
    }

    private fun recordPrepareFailure(appContext: Context, e: EngineDataPrepareException) {
        val existing = EngineDataPrepareTelemetry.load(appContext.filesDir)
        if (existing?.status != "failed" || existing.lastError != RustDiagnosticsCollector.redactDiagnosticText(e.redactedDetails)) {
            saveFailureTelemetry(
                appContext = appContext,
                failedPhase = e.failedPhase,
                redactedDetails = e.redactedDetails,
                timings = existing?.phaseTimingsMs.orEmpty(),
                peakRssKb = maxOf(_uiState.value.peakRssKb, existing?.peakRssKb ?: 0L),
                warnings = _uiState.value.warnings
            )
        }
        _uiState.value = PrepareUiState(
            running = false,
            phase = appContext.getString(R.string.developer_prepare_failed),
            detail = e.redactedMessage,
            prepared = isPreparedFallback(appContext.filesDir),
            redactedError = e.redactedDetails,
            warnings = _uiState.value.warnings
        )
    }

    private fun recordUnexpectedFailure(appContext: Context, t: Throwable) {
        val stepPhase = _uiState.value.phase.ifBlank { "prepare" }
        val errDetails = EngineDataPrepareSupport.buildRedactedErrorDetails(
            stepName = stepPhase,
            reason = "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
        )
        saveFailureTelemetry(
            appContext = appContext,
            failedPhase = stepPhase,
            redactedDetails = errDetails,
            peakRssKb = _uiState.value.peakRssKb,
            warnings = _uiState.value.warnings
        )
        _uiState.value = PrepareUiState(
            running = false,
            phase = appContext.getString(R.string.developer_prepare_failed),
            detail = RustDiagnosticsCollector.redactDiagnosticText(t.message ?: t.javaClass.simpleName),
            prepared = isPreparedFallback(appContext.filesDir),
            redactedError = errDetails,
            warnings = _uiState.value.warnings
        )
    }

    fun cancel(context: Context) {
        runCatching {
            if (BlazePrepareNative.ensureLoaded()) {
                BlazePrepareNative.nativeRequestCancel()
            }
        }
        activeJob?.cancel()
        runCatching { stopAllNonMainProcesses(context.applicationContext) }
    }

    suspend fun runPreparation(
        context: Context,
        onStatusUpdate: ((phase: String, fraction: Float?) -> Unit)? = null
    ): Phase2OutputJson = withContext(Dispatchers.IO) {
        val totalStartNs = System.nanoTime()
        val timings = linkedMapOf<String, Long>()
        var peakRssKb = 0L
        var prepareWarnings: List<String> = emptyList()
        var currentPhaseName = "prepare"
        var currentPhaseStartMs = System.currentTimeMillis()
        var lastHeartbeatAtMs = 0L
        var latestHeartbeat: JvmWorkerHeartbeat? = null
        val allStepAttempts = mutableListOf<JvmStepAttemptRecord>()
        var latestWorkerLogTail60: List<String> = emptyList()

        fun persistRunningTelemetry() {
            EngineDataPrepareTelemetry.save(
                context.filesDir,
                EngineDataPrepareMetrics(
                    status = "running",
                    minecraftVersion = EngineDataPrepareSupport.TARGET_MINECRAFT_VERSION,
                    totalMs = (System.nanoTime() - totalStartNs) / 1_000_000L,
                    phaseTimingsMs = timings.toMap(),
                    peakRssKb = peakRssKb,
                    warnings = prepareWarnings,
                    updatedAtMs = System.currentTimeMillis(),
                    currentPhase = currentPhaseName,
                    phaseStartMs = currentPhaseStartMs,
                    lastHeartbeatAtMs = lastHeartbeatAtMs,
                    heartbeat = latestHeartbeat,
                    stepAttempts = allStepAttempts.toList(),
                    workerLogTail = latestWorkerLogTail60
                )
            )
        }

        fun beginTelemetryPhase(phaseName: String) {
            currentPhaseName = phaseName
            currentPhaseStartMs = System.currentTimeMillis()
            persistRunningTelemetry()
        }

        fun updateProgress(phase: String, detail: String = "", fraction: Float? = null, rssKb: Long = peakRssKb) {
            peakRssKb = maxOf(peakRssKb, rssKb)
            _uiState.value = PrepareUiState(
                running = true,
                phase = phase,
                detail = detail,
                progressFraction = fraction,
                peakRssKb = peakRssKb,
                prepared = isPreparedFallback(context.filesDir),
                warnings = prepareWarnings
            )
            onStatusUpdate?.invoke(phase, fraction)
        }

        if (!BlazePrepareNative.ensureLoaded()) {
            val err = BlazePrepareNative.lastLoadError() ?: "libblaze_prepare.so missing"
            val details = EngineDataPrepareSupport.buildRedactedErrorDetails("load_native", err)
            saveFailureTelemetry(context, "load_native", details, timings, peakRssKb, prepareWarnings, "load_native", currentPhaseStartMs)
            throw EngineDataPrepareException(err, details, failedPhase = "load_native")
        }

        // Phase 0: unpack bundled lock, bedrock-controls, and MIT Java exporter sources
        beginTelemetryPhase("unpack_assets")
        updateProgress("Unpacking pinned exporter sources", "", 0.02f)
        val unpackStartNs = System.nanoTime()
        val assetsWorkspace = try {
            unpackBundledAssets(context)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val reason = "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
            val details = EngineDataPrepareSupport.buildRedactedErrorDetails("unpack_assets", reason)
            saveFailureTelemetry(context, "unpack_assets", details, timings, peakRssKb, prepareWarnings, currentPhaseName, currentPhaseStartMs)
            throw EngineDataPrepareException(
                RustDiagnosticsCollector.redactDiagnosticText(reason),
                details,
                failedPhase = "unpack_assets"
            )
        }
        timings["unpack_assets"] = (System.nanoTime() - unpackStartNs) / 1_000_000L

        // Phase 1 (Rust): pin verification, ZL2 reuse, downloads, bundler unpack, client resources, worldgen
        beginTelemetryPhase("rust_phase1")
        BlazePrepareNative.nativeResetCancel()
        val config = PrepareConfigJson(
            filesDir = context.filesDir.absolutePath,
            gameHome = runCatching { getGameHome() }.getOrNull()?.takeIf { it.isNotBlank() },
            lockPath = assetsWorkspace.lockFile.absolutePath,
            controlsManifestPath = assetsWorkspace.controlsManifestFile.absolutePath,
            paritySourcesDir = assetsWorkspace.paritySourceRoot.absolutePath,
            downloadWorkers = 6
        )
        val configJson = BlazePrepareNative.encodeConfig(config)

        val pollJob = launch {
            while (isActive) {
                val snap = BlazePrepareNative.parseProgress(BlazePrepareNative.nativeGetProgressJson())
                val frac = if (snap.total > 0L) {
                    0.05f + 0.35f * (snap.done.toFloat() / snap.total.toFloat()).coerceIn(0f, 1f)
                } else {
                    null
                }
                if (snap.phase.isNotBlank()) {
                    val detail = if (snap.total > 0L) "${snap.done}/${snap.total}" else ""
                    updateProgress(snap.phase, detail, frac, snap.peakRssKb)
                }
                delay(250L)
            }
        }

        val phase1 = try {
            val raw1 = BlazePrepareNative.nativePreparePhase1(configJson)
            when (val env = BlazePrepareNative.parseEnvelope(raw1, Phase1OutputJson::class.java)) {
                is PrepareEnvelope.Success -> env.data
                is PrepareEnvelope.Failure -> {
                    val details = EngineDataPrepareSupport.buildRedactedErrorDetails("rust_phase1", env.error)
                    saveFailureTelemetry(
                        appContext = context,
                        failedPhase = "rust_phase1",
                        redactedDetails = details,
                        timings = timings,
                        peakRssKb = maxOf(peakRssKb, env.peakRssKb),
                        warnings = prepareWarnings,
                        currentPhase = currentPhaseName,
                        phaseStartMs = currentPhaseStartMs
                    )
                    throw EngineDataPrepareException(
                        RustDiagnosticsCollector.redactDiagnosticText(env.error),
                        details,
                        failedPhase = "rust_phase1"
                    )
                }
            }
        } finally {
            pollJob.cancel()
        }
        timings["rust_phase1"] = phase1.elapsedMs
        peakRssKb = maxOf(peakRssKb, phase1.peakRssKb, BlazePrepareNative.nativePeakRssKb())
        @Suppress("USELESS_ELVIS")
        prepareWarnings = (phase1.warnings ?: emptyList())
            .filter { it.isNotBlank() }
            .map { RustDiagnosticsCollector.redactDiagnosticText(it) }
        if (prepareWarnings.isNotEmpty()) {
            updateProgress(
                phase = "Warning: ${prepareWarnings.first()}",
                detail = prepareWarnings.joinToString("; "),
                fraction = 0.41f,
                rssKb = peakRssKb
            )
        }

        // Ensure OpenJDK 25 (Internal-25) is unpacked for datagen & parity compilation
        beginTelemetryPhase("ensure_jre25")
        updateProgress("Checking OpenJDK 25 runtime", Jre.JRE_25.jreName, 0.42f)
        val jreStartNs = System.nanoTime()
        try {
            ensureInternalJre25(context)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val reason = "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
            val details = EngineDataPrepareSupport.buildRedactedErrorDetails("ensure_jre25", reason)
            saveFailureTelemetry(context, "ensure_jre25", details, timings, peakRssKb, prepareWarnings, currentPhaseName, currentPhaseStartMs)
            throw EngineDataPrepareException(
                RustDiagnosticsCollector.redactDiagnosticText(reason),
                details,
                failedPhase = "ensure_jre25"
            )
        }
        timings["ensure_jre25"] = (System.nanoTime() - jreStartNs) / 1_000_000L

        // Run sequential :jvm steps (javac + vanilla datagen + parity exporters)
        val jvmStartNs = System.nanoTime()
        runCatching { getGameHome() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it).mkdirs() }
        try {
            runSequentialJavaExporters(
                context = context,
                plan = phase1.javaPlan,
                parityPackageDir = assetsWorkspace.parityPackageDir,
                paritySourceRoot = assetsWorkspace.paritySourceRoot,
                onStep = { stepIdx, totalSteps, label ->
                    val frac = 0.45f + 0.42f * (stepIdx.toFloat() / totalSteps.toFloat())
                    beginTelemetryPhase("jvm_exporters: $label")
                    updateProgress(label, "JVM step $stepIdx/$totalSteps", frac)
                },
                onHeartbeat = { stepIdx, totalSteps, label, hb, logTail60, attemptsSnapshot ->
                    val frac = 0.45f + 0.42f * (stepIdx.toFloat() / totalSteps.toFloat())
                    lastHeartbeatAtMs = hb.recordedAtMs
                    latestHeartbeat = hb
                    latestWorkerLogTail60 = logTail60
                    val mergedAttempts = allStepAttempts + attemptsSnapshot
                    val freeLabel = if (hb.freeHeapMb >= 0L) "${hb.freeHeapMb}MB free" else "heap ?"
                    val statusSuffix = if (hb.javacRunning) "javac running" else "jvm active"
                    val detailText = "JVM step $stepIdx/$totalSteps • attempt ${hb.attempt} • ${hb.elapsedSeconds}s • $freeLabel • $statusSuffix"
                    _uiState.value = PrepareUiState(
                        running = true,
                        phase = label,
                        detail = detailText,
                        progressFraction = frac,
                        peakRssKb = peakRssKb,
                        prepared = isPreparedFallback(context.filesDir),
                        warnings = prepareWarnings
                    )
                    onStatusUpdate?.invoke("$label (${hb.elapsedSeconds}s, $freeLabel, attempt ${hb.attempt})", frac)
                    EngineDataPrepareTelemetry.save(
                        context.filesDir,
                        EngineDataPrepareMetrics(
                            status = "running",
                            minecraftVersion = EngineDataPrepareSupport.TARGET_MINECRAFT_VERSION,
                            totalMs = (System.nanoTime() - totalStartNs) / 1_000_000L,
                            phaseTimingsMs = timings.toMap(),
                            peakRssKb = peakRssKb,
                            warnings = prepareWarnings,
                            updatedAtMs = System.currentTimeMillis(),
                            currentPhase = currentPhaseName,
                            phaseStartMs = currentPhaseStartMs,
                            lastHeartbeatAtMs = lastHeartbeatAtMs,
                            heartbeat = latestHeartbeat,
                            stepAttempts = mergedAttempts,
                            workerLogTail = latestWorkerLogTail60
                        )
                    )
                },
                onStepFinished = { result ->
                    allStepAttempts.addAll(result.attempts)
                    if (result.warnings.isNotEmpty()) {
                        prepareWarnings = (prepareWarnings + result.warnings).distinct()
                    }
                    if (result.logTail60.isNotEmpty()) {
                        latestWorkerLogTail60 = result.logTail60
                    }
                    result.lastHeartbeat?.let { hb ->
                        latestHeartbeat = hb
                        lastHeartbeatAtMs = hb.recordedAtMs
                    }
                    persistRunningTelemetry()
                }
            )
        } catch (e: EngineDataPrepareException) {
            val latestTail = EngineDataPrepareSupport.readTailLines(
                LATEST_PROCESS_LOG_FILE,
                EngineDataPrepareSupport.COPY_REPORT_LOG_TAIL_LINES
            ).map { RustDiagnosticsCollector.redactDiagnosticText(it) }
            if (latestTail.isNotEmpty()) {
                latestWorkerLogTail60 = latestTail
            }
            val existing = EngineDataPrepareTelemetry.load(context.filesDir)
            saveFailureTelemetry(
                appContext = context,
                failedPhase = e.failedPhase,
                redactedDetails = e.redactedDetails,
                timings = timings,
                peakRssKb = peakRssKb,
                warnings = prepareWarnings,
                currentPhase = e.failedPhase,
                phaseStartMs = currentPhaseStartMs,
                lastHeartbeatAtMs = existing?.lastHeartbeatAtMs ?: lastHeartbeatAtMs,
                heartbeat = existing?.heartbeat ?: latestHeartbeat,
                stepAttempts = existing?.safeStepAttempts().takeIf { !it.isNullOrEmpty() } ?: allStepAttempts.toList(),
                workerLogTail = latestWorkerLogTail60
            )
            throw e
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val reason = "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
            val details = EngineDataPrepareSupport.buildRedactedErrorDetails("jvm_exporters", reason)
            saveFailureTelemetry(
                appContext = context,
                failedPhase = "jvm_exporters",
                redactedDetails = details,
                timings = timings,
                peakRssKb = peakRssKb,
                warnings = prepareWarnings,
                currentPhase = currentPhaseName,
                phaseStartMs = currentPhaseStartMs,
                lastHeartbeatAtMs = lastHeartbeatAtMs,
                heartbeat = latestHeartbeat,
                stepAttempts = allStepAttempts.toList(),
                workerLogTail = latestWorkerLogTail60
            )
            throw EngineDataPrepareException(
                RustDiagnosticsCollector.redactDiagnosticText(reason),
                details,
                failedPhase = "jvm_exporters"
            )
        }
        timings["jvm_exporters"] = (System.nanoTime() - jvmStartNs) / 1_000_000L

        // Phase 2 (Rust): assemble reports, survival rules, block flags, engine-data.zip, atomic swap
        beginTelemetryPhase("rust_phase2")
        updateProgress("Finalizing engine-data.zip", "", 0.90f)
        val pollJob2 = launch {
            while (isActive) {
                val snap = BlazePrepareNative.parseProgress(BlazePrepareNative.nativeGetProgressJson())
                if (snap.phase.isNotBlank()) {
                    val detail = if (snap.total > 0L) "${snap.done}/${snap.total}" else ""
                    updateProgress(snap.phase, detail, 0.94f, snap.peakRssKb)
                }
                delay(200L)
            }
        }
        val phase2 = try {
            val raw2 = BlazePrepareNative.nativePreparePhase2(configJson)
            when (val env = BlazePrepareNative.parseEnvelope(raw2, Phase2OutputJson::class.java)) {
                is PrepareEnvelope.Success -> env.data
                is PrepareEnvelope.Failure -> {
                    val details = EngineDataPrepareSupport.buildRedactedErrorDetails("rust_phase2", env.error)
                    saveFailureTelemetry(
                        appContext = context,
                        failedPhase = "rust_phase2",
                        redactedDetails = details,
                        timings = timings,
                        peakRssKb = maxOf(peakRssKb, env.peakRssKb),
                        warnings = prepareWarnings,
                        currentPhase = currentPhaseName,
                        phaseStartMs = currentPhaseStartMs,
                        lastHeartbeatAtMs = lastHeartbeatAtMs,
                        heartbeat = latestHeartbeat,
                        stepAttempts = allStepAttempts.toList(),
                        workerLogTail = latestWorkerLogTail60
                    )
                    throw EngineDataPrepareException(
                        RustDiagnosticsCollector.redactDiagnosticText(env.error),
                        details,
                        failedPhase = "rust_phase2"
                    )
                }
            }
        } finally {
            pollJob2.cancel()
        }
        timings["rust_phase2"] = phase2.elapsedMs
        peakRssKb = maxOf(peakRssKb, phase2.peakRssKb, BlazePrepareNative.nativePeakRssKb())

        val totalMs = (System.nanoTime() - totalStartNs) / 1_000_000L
        val summary = "Prepared 26.3 (${phase2.engineDataEntries} zip entries, ${phase1.downloadStats.filesReused} reused, ${phase1.downloadStats.filesDownloaded} downloaded, ${totalMs / 1000.0}s)"
        EngineDataPrepareTelemetry.save(
            context.filesDir,
            EngineDataPrepareMetrics(
                status = "ready",
                minecraftVersion = EngineDataPrepareSupport.TARGET_MINECRAFT_VERSION,
                totalMs = totalMs,
                phaseTimingsMs = timings,
                peakRssKb = peakRssKb,
                filesReused = phase1.downloadStats.filesReused,
                filesDownloaded = phase1.downloadStats.filesDownloaded,
                engineDataEntries = phase2.engineDataEntries,
                warnings = prepareWarnings,
                updatedAtMs = System.currentTimeMillis(),
                currentPhase = "ready",
                phaseStartMs = currentPhaseStartMs,
                lastHeartbeatAtMs = lastHeartbeatAtMs,
                heartbeat = latestHeartbeat,
                stepAttempts = allStepAttempts.toList(),
                workerLogTail = latestWorkerLogTail60
            )
        )
        _uiState.value = PrepareUiState(
            running = false,
            phase = context.getString(R.string.developer_prepare_ready),
            detail = summary,
            progressFraction = 1.0f,
            peakRssKb = peakRssKb,
            prepared = true,
            completedSummary = summary,
            warnings = prepareWarnings
        )
        phase2
    }

    private data class UnpackedAssetsWorkspace(
        val lockFile: File,
        val controlsManifestFile: File,
        val paritySourceRoot: File,
        val parityPackageDir: File
    )

    private fun unpackBundledAssets(context: Context): UnpackedAssetsWorkspace {
        val baseDir = File(context.filesDir, ".local/blaze-prepare-assets")
        baseDir.mkdirs()

        fun copyAsset(assetPath: String, destFile: File) {
            destFile.parentFile?.mkdirs()
            val partial = File(destFile.parentFile, "${destFile.name}.partial")
            context.assets.open(assetPath).use { input ->
                partial.outputStream().use { output -> input.copyTo(output) }
            }
            if (!partial.renameTo(destFile)) {
                partial.copyTo(destFile, overwrite = true)
                partial.delete()
            }
        }

        val lockFile = File(baseDir, EngineDataPrepareSupport.LOCK_ASSET_NAME)
        val controlsFile = File(baseDir, EngineDataPrepareSupport.CONTROLS_ASSET_NAME)
        copyAsset(EngineDataPrepareSupport.LOCK_ASSET_NAME, lockFile)
        copyAsset(EngineDataPrepareSupport.CONTROLS_ASSET_NAME, controlsFile)

        val parityRoot = File(baseDir, "horizon-parity")
        val parityPkgDir = File(parityRoot, "dev/horizonlab/parity")
        parityPkgDir.mkdirs()

        val bundledJavaFiles = context.assets.list(EngineDataPrepareSupport.PARITY_ASSET_SUBDIR)
            ?.filter { it.endsWith(".java") }
            .orEmpty()
        if (bundledJavaFiles.isEmpty()) {
            throw IOException("Missing bundled Java exporter sources in assets/${EngineDataPrepareSupport.PARITY_ASSET_SUBDIR}")
        }
        bundledJavaFiles.forEach { fileName ->
            copyAsset(
                "${EngineDataPrepareSupport.PARITY_ASSET_SUBDIR}/$fileName",
                File(parityPkgDir, fileName)
            )
        }
        copyAsset(
            EngineDataPrepareSupport.SHIM_ASSET_PATH,
            File(parityPkgDir, "PrepareBatchShim.java")
        )
        return UnpackedAssetsWorkspace(
            lockFile = lockFile,
            controlsManifestFile = controlsFile,
            paritySourceRoot = parityRoot,
            parityPackageDir = parityPkgDir
        )
    }

    private suspend fun ensureInternalJre25(context: Context) {
        val unpackTask = UnpackJreTask(context, Jre.JRE_25)
        if (unpackTask.isCheckFailed()) {
            throw IllegalStateException("Internal-25 OpenJDK runtime is unavailable for arm64-v8a on this device")
        }
        if (unpackTask.checkState() != InstallableItem.State.FINISHED) {
            unpackTask.run()
        }
        RuntimesManager.getRuntimeHome(Jre.JRE_25.jreName)
    }

    private suspend fun runSequentialJavaExporters(
        context: Context,
        plan: JavaPlanJson,
        parityPackageDir: File,
        paritySourceRoot: File,
        onStep: (step: Int, total: Int, label: String) -> Unit,
        onHeartbeat: suspend (
            step: Int,
            total: Int,
            label: String,
            heartbeat: JvmWorkerHeartbeat,
            logTail60: List<String>,
            attemptsSoFar: List<JvmStepAttemptRecord>
        ) -> Unit = { _, _, _, _, _, _ -> },
        onStepFinished: (JvmStepRunResult) -> Unit = {}
    ) {
        val totalRamBytes = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(info)
            info.totalMem
        }.getOrNull()
        val heapMb = EngineDataPrepareSupport.recommendJvmHeapMb(totalRamBytes)
        val commonFlags = EngineDataPrepareSupport.buildCommonJvmFlags(heapMb)
        val javacFlags = EngineDataPrepareSupport.buildJavacJvmFlags(heapMb)
        val jvmUserHome = File(context.cacheDir, "blaze_prepare_jvm_home").apply { mkdirs() }.absolutePath
        val workDir = File(plan.workDir)

        val serverSrcs = EngineDataPrepareSupport.SERVER_EXPORTER_FILES.map { name ->
            val f = File(parityPackageDir, name)
            if (!f.isFile) throw IOException("Missing server exporter source: ${f.absolutePath}")
            f
        }
        val serverArgFile = File(workDir, "javac_server.args").apply {
            writeText(
                EngineDataPrepareSupport.formatJavacArgFile(
                    classpath = plan.serverClasspath,
                    sourcePath = paritySourceRoot.absolutePath,
                    outputDir = plan.serverClassesDir,
                    javaSourceFiles = serverSrcs,
                    verbose = true
                )
            )
        }

        val clientSrcs = EngineDataPrepareSupport.CLIENT_EXPORTER_FILES.map { name ->
            val f = File(parityPackageDir, name)
            if (!f.isFile) throw IOException("Missing client exporter source: ${f.absolutePath}")
            f
        }
        val clientArgFile = File(workDir, "javac_client.args").apply {
            writeText(
                EngineDataPrepareSupport.formatJavacArgFile(
                    classpath = plan.clientClasspath,
                    sourcePath = paritySourceRoot.absolutePath,
                    outputDir = plan.clientClassesDir,
                    javaSourceFiles = clientSrcs,
                    verbose = true
                )
            )
        }

        val q = EngineDataPrepareSupport::quoteCmdArg
        val serverBatchCp = "${plan.serverClassesDir}:${plan.serverClasspath}"
        val clientBatchCp = "${plan.clientClassesDir}:${plan.clientClasspath}"

        val steps = listOf(
            JvmStepSpec(
                name = "Compiling server parity exporters (javac 25)",
                jvmArgs = "$javacFlags -m jdk.compiler/com.sun.tools.javac.Main @${q(serverArgFile.absolutePath)}",
                isJavac = true,
                timeoutMs = EngineDataPrepareSupport.DEFAULT_JAVAC_STEP_TIMEOUT_MS,
                initialHeapMb = heapMb,
                classpathEntriesCount = EngineDataPrepareSupport.countClasspathEntries(plan.serverClasspath),
                retryOnTimeout = JvmStepRetryConfig(
                    originalClasspath = plan.serverClasspath,
                    initialHeapMb = heapMb,
                    buildRetryJvmArgs = { reducedCp, loweredHeapMb ->
                        val retryArgFile = File(workDir, "javac_server_retry.args").apply {
                            writeText(
                                EngineDataPrepareSupport.formatJavacArgFile(
                                    classpath = reducedCp,
                                    sourcePath = paritySourceRoot.absolutePath,
                                    outputDir = plan.serverClassesDir,
                                    javaSourceFiles = serverSrcs,
                                    verbose = true
                                )
                            )
                        }
                        "${EngineDataPrepareSupport.buildJavacJvmFlags(loweredHeapMb)} -m jdk.compiler/com.sun.tools.javac.Main @${q(retryArgFile.absolutePath)}"
                    }
                )
            ),
            JvmStepSpec(
                name = "Compiling client parity exporters (javac 25)",
                jvmArgs = "$javacFlags -m jdk.compiler/com.sun.tools.javac.Main @${q(clientArgFile.absolutePath)}",
                isJavac = true,
                timeoutMs = EngineDataPrepareSupport.DEFAULT_JAVAC_STEP_TIMEOUT_MS,
                initialHeapMb = heapMb,
                classpathEntriesCount = EngineDataPrepareSupport.countClasspathEntries(plan.clientClasspath),
                retryOnTimeout = JvmStepRetryConfig(
                    originalClasspath = plan.clientClasspath,
                    initialHeapMb = heapMb,
                    buildRetryJvmArgs = { reducedCp, loweredHeapMb ->
                        val retryArgFile = File(workDir, "javac_client_retry.args").apply {
                            writeText(
                                EngineDataPrepareSupport.formatJavacArgFile(
                                    classpath = reducedCp,
                                    sourcePath = paritySourceRoot.absolutePath,
                                    outputDir = plan.clientClassesDir,
                                    javaSourceFiles = clientSrcs,
                                    verbose = true
                                )
                            )
                        }
                        "${EngineDataPrepareSupport.buildJavacJvmFlags(loweredHeapMb)} -m jdk.compiler/com.sun.tools.javac.Main @${q(retryArgFile.absolutePath)}"
                    }
                )
            ),
            JvmStepSpec(
                name = "Running Minecraft 26.3 server datagen reports",
                jvmArgs = "$commonFlags -cp ${q(plan.serverClasspath)} net.minecraft.data.Main --reports --output ${q(plan.datagenOutDir)}",
                isJavac = false,
                timeoutMs = EngineDataPrepareSupport.DEFAULT_EXPORTER_STEP_TIMEOUT_MS,
                initialHeapMb = heapMb,
                classpathEntriesCount = EngineDataPrepareSupport.countClasspathEntries(plan.serverClasspath)
            ),
            JvmStepSpec(
                name = "Running server parity exporters",
                jvmArgs = "$commonFlags -cp ${q(serverBatchCp)} dev.horizonlab.parity.PrepareBatchShim ${q(plan.serverTasksJsonl)}",
                isJavac = false,
                timeoutMs = EngineDataPrepareSupport.DEFAULT_EXPORTER_STEP_TIMEOUT_MS,
                initialHeapMb = heapMb,
                classpathEntriesCount = EngineDataPrepareSupport.countClasspathEntries(serverBatchCp)
            ),
            JvmStepSpec(
                name = "Exporting vanilla command registries",
                jvmArgs = "$commonFlags -cp ${q(serverBatchCp)} dev.horizonlab.parity.VanillaCommandParseOracle registries ${q(plan.commandRegistriesDir)}",
                isJavac = false,
                timeoutMs = EngineDataPrepareSupport.DEFAULT_EXPORTER_STEP_TIMEOUT_MS,
                initialHeapMb = heapMb,
                classpathEntriesCount = EngineDataPrepareSupport.countClasspathEntries(serverBatchCp)
            ),
            JvmStepSpec(
                name = "Exporting vanilla creative inventory tabs",
                jvmArgs = "$commonFlags -cp ${q(serverBatchCp)} dev.horizonlab.parity.VanillaCreativeTabsExporter ${q(plan.creativeInventoryJson)}",
                isJavac = false,
                timeoutMs = EngineDataPrepareSupport.DEFAULT_EXPORTER_STEP_TIMEOUT_MS,
                initialHeapMb = heapMb,
                classpathEntriesCount = EngineDataPrepareSupport.countClasspathEntries(serverBatchCp)
            ),
            JvmStepSpec(
                name = "Running client model and mob parity exporters",
                jvmArgs = "$commonFlags -cp ${q(clientBatchCp)} dev.horizonlab.parity.PrepareBatchShim ${q(plan.clientTasksJsonl)}",
                isJavac = false,
                timeoutMs = EngineDataPrepareSupport.DEFAULT_EXPORTER_STEP_TIMEOUT_MS,
                initialHeapMb = heapMb,
                classpathEntriesCount = EngineDataPrepareSupport.countClasspathEntries(clientBatchCp)
            )
        )

        stopAllNonMainProcesses(GlobalContext)
        for (i in 0 until 20) {
            if (listBlockingProcesses(GlobalContext).isEmpty()) break
            delay(250L)
        }

        steps.forEachIndexed { idx, step ->
            val stepNum = idx + 1
            onStep(stepNum, steps.size, step.name)
            val result = EngineDataPrepareSupport.runJvmStepWithMonitor(
                step = step,
                heartbeatIntervalMs = EngineDataPrepareSupport.DEFAULT_HEARTBEAT_INTERVAL_MS,
                runWorker = { _, args, title ->
                    startJvmServiceAndWaitExit(
                        jvmArgs = args,
                        jreName = Jre.JRE_25.jreName,
                        userHome = jvmUserHome,
                        postSummary = title,
                        postProgress = NoticeProgress(steps.size, stepNum)
                    )
                },
                killWorker = { killJvmWorkerProcess(context) },
                isWorkerAlive = { findJvmWorkerPid(context) != null },
                readLogLines = {
                    EngineDataPrepareSupport.readTailLines(
                        LATEST_PROCESS_LOG_FILE,
                        EngineDataPrepareSupport.GC_LOG_SCAN_LINES
                    )
                },
                sampleFallbackFreeHeapMb = { configuredMb ->
                    sampleJvmFallbackFreeHeapMb(context, configuredMb)
                },
                onHeartbeat = { hb, logTail60, attemptsSoFar ->
                    onHeartbeat(stepNum, steps.size, step.name, hb, logTail60, attemptsSoFar)
                },
                onRetryNotice = { notice, _, _ ->
                    _uiState.value = _uiState.value.copy(
                        phase = "${step.name} (retry)",
                        detail = notice
                    )
                }
            )
            onStepFinished(result)
        }
    }

    fun isPreparedFallback(filesDir: File): Boolean {
        val verDir = File(filesDir, ".local/horizon-client/${EngineDataPrepareSupport.TARGET_MINECRAFT_VERSION}")
        return File(filesDir, "engine-data.zip").isFile &&
            File(verDir, "horizon-resources.json").isFile &&
            File(verDir, "android-downloads-v2.json").isFile &&
            File(verDir, "audio-complete.json").isFile
    }
}
