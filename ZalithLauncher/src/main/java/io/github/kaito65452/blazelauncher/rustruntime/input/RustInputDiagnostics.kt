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
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package io.github.kaito65452.blazelauncher.rustruntime.input

import java.util.concurrent.atomic.AtomicLong

/**
 * Pure mapping from overlay-local touch coordinates to the normalized (0..1) cursor position that the
 * engine multiplies by its own surface size. The overlay area (Kotlin viewport, e.g. 1463x694) can differ
 * from the engine surface (e.g. 1520x720), so we normalize against the full window when known.
 */
object RustTouchGeometry {
    data class Normalized(val x: Float, val y: Float)

    fun normalize(
        x: Float,
        y: Float,
        areaWidth: Int,
        areaHeight: Int,
        offsetX: Int = 0,
        offsetY: Int = 0,
        windowWidth: Int = 0,
        windowHeight: Int = 0
    ): Normalized {
        val useWindow = windowWidth > 0 && windowHeight > 0
        val w = (if (useWindow) windowWidth else areaWidth).coerceAtLeast(1).toFloat()
        val h = (if (useWindow) windowHeight else areaHeight).coerceAtLeast(1).toFloat()
        val ox = if (useWindow) offsetX else 0
        val oy = if (useWindow) offsetY else 0
        val nx = if (x.isFinite()) ((x + ox) / w).coerceIn(0f, 1f) else 0.5f
        val ny = if (y.isFinite()) ((y + oy) / h).coerceIn(0f, 1f) else 0.5f
        return Normalized(nx, ny)
    }
}

/** Where a touch sequence goes. Desktop mode: Activity gate is the only door; never the native path. */
object RustTouchRouting {
    enum class Route { ZL2_OVERLAY, ENGINE_NATIVE }

    fun route(desktopInputEnabled: Boolean, overlayAttached: Boolean): Route =
        if (desktopInputEnabled && overlayAttached) Route.ZL2_OVERLAY else Route.ENGINE_NATIVE

    /** In desktop mode the Activity gate must consume the event even without an overlay (no leak). */
    fun consumeAtActivity(desktopInputEnabled: Boolean): Boolean = desktopInputEnabled
}

/** Counters surfaced in the Copy report "Input Bridge" section and the optional on-screen debug overlay. */
object RustInputDiagnostics {
    val touchSeenByActivity = AtomicLong(0)
    val touchSeenByOverlay = AtomicLong(0)
    val touchRoutedToZl2 = AtomicLong(0)
    val overlayComposedCount = AtomicLong(0)

    @Volatile var overlayAttached: Boolean = false
    @Volatile var overlayVisible: Boolean = false
    @Volatile var overlayWidth: Int = 0
    @Volatile var overlayHeight: Int = 0
    @Volatile var overlayWindowType: Int = 0
    @Volatile var overlayZOrderAboveSurface: Boolean = false
    @Volatile var overlayError: String = ""
    @Volatile var lastTouchRawX: Float = Float.NaN
    @Volatile var lastTouchRawY: Float = Float.NaN
    @Volatile var lastMappedX: Float = Float.NaN
    @Volatile var lastMappedY: Float = Float.NaN

    fun reset() {
        touchSeenByActivity.set(0)
        touchSeenByOverlay.set(0)
        touchRoutedToZl2.set(0)
        overlayComposedCount.set(0)
        overlayAttached = false
        overlayVisible = false
        overlayWidth = 0
        overlayHeight = 0
        overlayWindowType = 0
        overlayZOrderAboveSurface = false
        overlayError = ""
        lastTouchRawX = Float.NaN
        lastTouchRawY = Float.NaN
        lastMappedX = Float.NaN
        lastMappedY = Float.NaN
    }

    private fun fmt(v: Float): String = if (v.isFinite()) "%.1f".format(java.util.Locale.ROOT, v) else "-"
    private fun fmt4(v: Float): String = if (v.isFinite()) "%.4f".format(java.util.Locale.ROOT, v) else "-"

    /** [leakedToEngine] = native raw_touch_ignored (touches that still reached winit); expected 0. */
    fun format(leakedToEngine: Long): String =
        "touch_seen_by_activity=${touchSeenByActivity.get()} " +
            "touch_seen_by_overlay=${touchSeenByOverlay.get()} " +
            "touch_routed_to_zl2=${touchRoutedToZl2.get()} " +
            "touch_leaked_to_engine=$leakedToEngine " +
            "overlay_attached=$overlayAttached overlay_visible=$overlayVisible " +
            "overlay_size=${overlayWidth}x$overlayHeight " +
            "overlay_zorder_above_surface=$overlayZOrderAboveSurface (window_type=$overlayWindowType) " +
            "overlay_composed_count=${overlayComposedCount.get()} " +
            "last_touch_xy_raw=${fmt(lastTouchRawX)},${fmt(lastTouchRawY)} " +
            "last_touch_xy_mapped=${fmt4(lastMappedX)},${fmt4(lastMappedY)}" +
            if (overlayError.isNotBlank()) " overlay_error=$overlayError" else ""

    private val RAW_TOUCH_REGEX = Regex("\"raw_touch_ignored\"\\s*:\\s*(\\d+)")

    fun parseNativeRawTouch(nativeDiagnostics: String): Long =
        RAW_TOUCH_REGEX.find(nativeDiagnostics)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
}
