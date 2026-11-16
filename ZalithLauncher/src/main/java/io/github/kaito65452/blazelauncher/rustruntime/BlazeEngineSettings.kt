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
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.movtery.zalithlauncher.setting.AllSettings
import java.io.File
import java.util.Locale

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

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun toJson(payload: BlazeEngineSettingsPayload): String {
        val obj = JsonObject().apply {
            addProperty("schema", payload.schema)
            addProperty("render_scale_percent", sanitizeRenderScale(payload.renderScalePercent))
            addProperty("touch_look_sensitivity", payload.touchLookSensitivity.coerceIn(0.25f, 3.0f).toDouble())
            addProperty("show_controls", payload.showControls)
            addProperty("source", payload.source.ifBlank { DEFAULT_SOURCE })
        }
        return gson.toJson(obj)
    }

    fun fromJsonOrNull(raw: String?): BlazeEngineSettingsPayload? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val elem = JsonParser.parseString(raw)
            if (!elem.isJsonObject) return@runCatching null
            val obj = elem.asJsonObject
            val schema = obj.get("schema")?.takeIf { it.isJsonPrimitive }?.asInt ?: SCHEMA_VERSION
            val scaleRaw = obj.get("render_scale_percent")?.takeIf { it.isJsonPrimitive }?.asInt ?: 100
            val scale = sanitizeRenderScale(scaleRaw)
            val sensRaw = obj.get("touch_look_sensitivity")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 1.0f
            val sens = if (sensRaw.isFinite()) sensRaw.coerceIn(0.25f, 3.0f) else 1.0f
            val showControls = obj.get("show_controls")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
            val source = obj.get("source")?.takeIf { it.isJsonPrimitive }?.asString?.ifBlank { DEFAULT_SOURCE }
                ?: DEFAULT_SOURCE
            BlazeEngineSettingsPayload(
                schema = schema,
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
        return "${parsed.source} ($SETTINGS_FILE_NAME: scale=${parsed.renderScalePercent}%, sens=${"%.2f".format(Locale.US, parsed.touchLookSensitivity)}x, controls=${parsed.showControls})"
    }
}
