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
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.components.InstallableItem
import com.movtery.zalithlauncher.components.jre.Jre
import com.movtery.zalithlauncher.components.jre.UnpackJreTask
import com.movtery.zalithlauncher.context.GlobalContext
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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
    val redactedDetails: String
) : RuntimeException(redactedMessage)

/**
 * Pure JVM helpers split out for deterministic unit testing without Android framework stubs.
 */
internal object EngineDataPrepareSupport {
    const val LOCK_ASSET_NAME = "research-lock.json"
    const val CONTROLS_ASSET_NAME = "bedrock-controls.json"
    const val SHIM_ASSET_PATH = "blaze-prepare/PrepareBatchShim.java"
    const val PARITY_ASSET_SUBDIR = "horizon-parity/dev/horizonlab/parity"
    const val TARGET_MINECRAFT_VERSION = "26.3"

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
            totalMb >= 6 * 1024 -> 768
            totalMb >= 4 * 1024 -> 640
            else -> 512
        }
    }

    fun quoteCmdArg(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    fun formatJavacArgFile(
        classpath: String,
        sourcePath: String,
        outputDir: String,
        javaSourceFiles: List<File>
    ): String = buildString {
        appendLine("--release")
        appendLine("25")
        appendLine("-proc:none")
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

    fun buildCommonJvmFlags(heapMb: Int): String = listOf(
        "-Djava.awt.headless=true",
        "-Xms256M",
        "-Xmx${heapMb}M",
        "-XX:TieredStopAtLevel=1",
        "-XX:+UseSerialGC"
    ).joinToString(" ")

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
        _uiState.value = _uiState.value.copy(
            prepared = prepared,
            peakRssKb = metrics?.peakRssKb ?: _uiState.value.peakRssKb,
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

    private fun recordPrepareFailure(appContext: Context, e: EngineDataPrepareException) {
        _uiState.value = PrepareUiState(
            running = false,
            phase = appContext.getString(R.string.developer_prepare_failed),
            detail = e.redactedMessage,
            prepared = isPreparedFallback(appContext.filesDir),
            redactedError = e.redactedDetails
        )
    }

    private fun recordUnexpectedFailure(appContext: Context, t: Throwable) {
        val errDetails = EngineDataPrepareSupport.buildRedactedErrorDetails(
            stepName = _uiState.value.phase.ifBlank { "prepare" },
            reason = "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
        )
        EngineDataPrepareTelemetry.save(
            appContext.filesDir,
            EngineDataPrepareMetrics(
                status = "failed",
                minecraftVersion = EngineDataPrepareSupport.TARGET_MINECRAFT_VERSION,
                lastError = errDetails,
                updatedAtMs = System.currentTimeMillis()
            )
        )
        _uiState.value = PrepareUiState(
            running = false,
            phase = appContext.getString(R.string.developer_prepare_failed),
            detail = RustDiagnosticsCollector.redactDiagnosticText(t.message ?: t.javaClass.simpleName),
            prepared = isPreparedFallback(appContext.filesDir),
            redactedError = errDetails
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
            throw EngineDataPrepareException(err, details)
        }

        // Phase 0: unpack bundled lock, bedrock-controls, and MIT Java exporter sources
        updateProgress("Unpacking pinned exporter sources", "", 0.02f)
        val unpackStartNs = System.nanoTime()
        val assetsWorkspace = unpackBundledAssets(context)
        timings["unpack_assets"] = (System.nanoTime() - unpackStartNs) / 1_000_000L

        // Phase 1 (Rust): pin verification, ZL2 reuse, downloads, bundler unpack, client resources, worldgen
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
                    EngineDataPrepareTelemetry.save(
                        context.filesDir,
                        EngineDataPrepareMetrics(
                            status = if (env.error.contains("cancel", ignoreCase = true)) "cancelled" else "failed",
                            minecraftVersion = EngineDataPrepareSupport.TARGET_MINECRAFT_VERSION,
                            phaseTimingsMs = timings,
                            peakRssKb = maxOf(peakRssKb, env.peakRssKb),
                            lastError = details,
                            warnings = prepareWarnings,
                            updatedAtMs = System.currentTimeMillis()
                        )
                    )
                    throw EngineDataPrepareException(
                        RustDiagnosticsCollector.redactDiagnosticText(env.error),
                        details
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
        updateProgress("Checking OpenJDK 25 runtime", Jre.JRE_25.jreName, 0.42f)
        val jreStartNs = System.nanoTime()
        ensureInternalJre25(context)
        timings["ensure_jre25"] = (System.nanoTime() - jreStartNs) / 1_000_000L

        // Run sequential :jvm steps (javac + vanilla datagen + parity exporters)
        val jvmStartNs = System.nanoTime()
        runSequentialJavaExporters(
            context = context,
            plan = phase1.javaPlan,
            parityPackageDir = assetsWorkspace.parityPackageDir,
            paritySourceRoot = assetsWorkspace.paritySourceRoot,
            onStep = { stepIdx, totalSteps, label ->
                val frac = 0.45f + 0.42f * (stepIdx.toFloat() / totalSteps.toFloat())
                updateProgress(label, "JVM step $stepIdx/$totalSteps", frac)
            }
        )
        timings["jvm_exporters"] = (System.nanoTime() - jvmStartNs) / 1_000_000L

        // Phase 2 (Rust): assemble reports, survival rules, block flags, engine-data.zip, atomic swap
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
                    EngineDataPrepareTelemetry.save(
                        context.filesDir,
                        EngineDataPrepareMetrics(
                            status = if (env.error.contains("cancel", ignoreCase = true)) "cancelled" else "failed",
                            minecraftVersion = EngineDataPrepareSupport.TARGET_MINECRAFT_VERSION,
                            phaseTimingsMs = timings,
                            peakRssKb = maxOf(peakRssKb, env.peakRssKb),
                            lastError = details,
                            warnings = prepareWarnings,
                            updatedAtMs = System.currentTimeMillis()
                        )
                    )
                    throw EngineDataPrepareException(
                        RustDiagnosticsCollector.redactDiagnosticText(env.error),
                        details
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
                updatedAtMs = System.currentTimeMillis()
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
        onStep: (step: Int, total: Int, label: String) -> Unit
    ) {
        val totalRamBytes = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(info)
            info.totalMem
        }.getOrNull()
        val heapMb = EngineDataPrepareSupport.recommendJvmHeapMb(totalRamBytes)
        val commonFlags = EngineDataPrepareSupport.buildCommonJvmFlags(heapMb)
        val jvmUserHome = File(context.cacheDir, "blaze_prepare_jvm_home").apply { mkdirs() }.absolutePath
        val workDir = File(plan.workDir)

        val serverArgFile = File(workDir, "javac_server.args").apply {
            val srcs = EngineDataPrepareSupport.SERVER_EXPORTER_FILES.map { name ->
                val f = File(parityPackageDir, name)
                if (!f.isFile) throw IOException("Missing server exporter source: ${f.absolutePath}")
                f
            }
            writeText(
                EngineDataPrepareSupport.formatJavacArgFile(
                    classpath = plan.serverClasspath,
                    sourcePath = paritySourceRoot.absolutePath,
                    outputDir = plan.serverClassesDir,
                    javaSourceFiles = srcs
                )
            )
        }

        val clientArgFile = File(workDir, "javac_client.args").apply {
            val srcs = EngineDataPrepareSupport.CLIENT_EXPORTER_FILES.map { name ->
                val f = File(parityPackageDir, name)
                if (!f.isFile) throw IOException("Missing client exporter source: ${f.absolutePath}")
                f
            }
            writeText(
                EngineDataPrepareSupport.formatJavacArgFile(
                    classpath = plan.clientClasspath,
                    sourcePath = paritySourceRoot.absolutePath,
                    outputDir = plan.clientClassesDir,
                    javaSourceFiles = srcs
                )
            )
        }

        val q = EngineDataPrepareSupport::quoteCmdArg
        val serverBatchCp = "${plan.serverClassesDir}:${plan.serverClasspath}"
        val clientBatchCp = "${plan.clientClassesDir}:${plan.clientClasspath}"

        data class JvmStep(val name: String, val args: String)

        val steps = listOf(
            JvmStep(
                "Compiling server parity exporters (javac 25)",
                "$commonFlags -m jdk.compiler/com.sun.tools.javac.Main @${q(serverArgFile.absolutePath)}"
            ),
            JvmStep(
                "Compiling client parity exporters (javac 25)",
                "$commonFlags -m jdk.compiler/com.sun.tools.javac.Main @${q(clientArgFile.absolutePath)}"
            ),
            JvmStep(
                "Running Minecraft 26.3 server datagen reports",
                "$commonFlags -cp ${q(plan.serverClasspath)} net.minecraft.data.Main --reports --output ${q(plan.datagenOutDir)}"
            ),
            JvmStep(
                "Running server parity exporters",
                "$commonFlags -cp ${q(serverBatchCp)} dev.horizonlab.parity.PrepareBatchShim ${q(plan.serverTasksJsonl)}"
            ),
            JvmStep(
                "Exporting vanilla command registries",
                "$commonFlags -cp ${q(serverBatchCp)} dev.horizonlab.parity.VanillaCommandParseOracle registries ${q(plan.commandRegistriesDir)}"
            ),
            JvmStep(
                "Exporting vanilla creative inventory tabs",
                "$commonFlags -cp ${q(serverBatchCp)} dev.horizonlab.parity.VanillaCreativeTabsExporter ${q(plan.creativeInventoryJson)}"
            ),
            JvmStep(
                "Running client model and mob parity exporters",
                "$commonFlags -cp ${q(clientBatchCp)} dev.horizonlab.parity.PrepareBatchShim ${q(plan.clientTasksJsonl)}"
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
            val exitCode = startJvmServiceAndWaitExit(
                jvmArgs = step.args,
                jreName = Jre.JRE_25.jreName,
                userHome = jvmUserHome,
                postSummary = step.name,
                postProgress = NoticeProgress(steps.size, stepNum)
            )
            if (exitCode != 0) {
                val tail = runCatching {
                    val f = LATEST_PROCESS_LOG_FILE
                    if (f.isFile) f.readLines().takeLast(30).joinToString("\n") else ""
                }.getOrDefault("")
                val details = EngineDataPrepareSupport.buildRedactedErrorDetails(
                    stepName = step.name,
                    reason = ":jvm process exited with code $exitCode",
                    processLogTail = tail
                )
                throw EngineDataPrepareException(
                    "${step.name} failed (exit=$exitCode)",
                    details
                )
            }
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
