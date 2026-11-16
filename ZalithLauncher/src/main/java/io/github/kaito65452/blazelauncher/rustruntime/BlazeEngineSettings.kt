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

import android.content.Context
import com.movtery.zalithlauncher.setting.AllSettings
import org.json.JSONObject
import java.io.File

data class BlazeEngineSettingsPayload(
    val schema: Int = 1,
    val renderScalePercent: Int = 100,
    val touchLookSensitivity: Float = 1.0f,
    val showControls: Boolean = true,
    val source: String = BlazeEngineSettings.DEFAULT_SOURCE
)

/**
 * Launcher-owned settings handoff for the embedded Rust engine (`filesDir/blaze_engine_settings.json`),
 * read once at engine startup so Horizon's in-engine settings pages are unnecessary.
 */
object BlazeEngineSettings {
    const val SCHEMA_VERSION: Int = 1
    const val SETTINGS_FILE_NAME: String = "blaze_engine_settings.json"
    const val DEFAULT_SOURCE: String = "blaze_launcher"

    fun sanitizeRenderScale(percent: Int): Int = percent.coerceIn(25, 100)

    fun sanitizeSensitivityPercent(percent: Int): Int = percent.coerceIn(25, 300)

    fun fromValues(
        renderScalePercent: Int,
        touchLookSensitivityPercent: Int,
        showControls: Boolean,
        source: String = DEFAULT_SOURCE
    ): BlazeEngineSettingsPayload {
        val scale = sanitizeRenderScale(renderScalePercent)
        val sens = sanitizeSensitivityPercent(touchLookSensitivityPercent) / 100.0f
        return BlazeEngineSettingsPayload(
            schema = SCHEMA_VERSION,
            renderScalePercent = scale,
            touchLookSensitivity = sens,
            showControls = showControls,
            source = source.ifBlank { DEFAULT_SOURCE }
        )
    }

    fun fromAllSettings(): BlazeEngineSettingsPayload = fromValues(
        renderScalePercent = AllSettings.rustRenderScalePercent.getValue(),
        touchLookSensitivityPercent = AllSettings.rustTouchLookSensitivityPercent.getValue(),
        showControls = AllSettings.rustShowControls.getValue()
    )

    fun toJson(payload: BlazeEngineSettingsPayload): String = JSONObject().apply {
        put("schema", payload.schema)
        put("render_scale_percent", sanitizeRenderScale(payload.renderScalePercent))
        put("touch_look_sensitivity", payload.touchLookSensitivity.coerceIn(0.25f, 3.0f).toDouble())
        put("show_controls", payload.showControls)
        put("source", payload.source.ifBlank { DEFAULT_SOURCE })
    }.toString(2)

    fun fromJsonOrNull(raw: String?): BlazeEngineSettingsPayload? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val obj = JSONObject(raw)
            val scale = sanitizeRenderScale(obj.optInt("render_scale_percent", 100))
            val sensRaw = obj.optDouble("touch_look_sensitivity", 1.0).toFloat()
            val sens = if (sensRaw.isFinite()) sensRaw.coerceIn(0.25f, 3.0f) else 1.0f
            val showControls = obj.optBoolean("show_controls", true)
            val source = obj.optString("source", DEFAULT_SOURCE).ifBlank { DEFAULT_SOURCE }
            BlazeEngineSettingsPayload(
                schema = obj.optInt("schema", SCHEMA_VERSION),
                renderScalePercent = scale,
                touchLookSensitivity = sens,
                showControls = showControls,
                source = source
            )
        }.getOrNull()
    }

    fun writeToDir(filesDir: File, payload: BlazeEngineSettingsPayload): Boolean {
        return runCatching {
            filesDir.mkdirs()
            val target = File(filesDir, SETTINGS_FILE_NAME)
            val partial = File(filesDir, "$SETTINGS_FILE_NAME.tmp")
            partial.writeText(toJson(payload) + "\n")
            if (!partial.renameTo(target)) {
                target.writeText(toJson(payload) + "\n")
                partial.delete()
            }
            true
        }.getOrDefault(false)
    }

    fun writeToFilesDir(context: Context): Boolean =
        writeToDir(context.filesDir, fromAllSettings())

    fun describeSettingsSource(filesDir: File): String {
        val file = File(filesDir, SETTINGS_FILE_NAME)
        if (!file.isFile) return "default (no $SETTINGS_FILE_NAME)"
        val parsed = fromJsonOrNull(runCatching { file.readText() }.getOrNull())
            ?: return "invalid ($SETTINGS_FILE_NAME)"
        return "${parsed.source} ($SETTINGS_FILE_NAME: scale=${parsed.renderScalePercent}%, sens=${"%.2f".format(parsed.touchLookSensitivity)}x, controls=${parsed.showControls})"
    }
}
