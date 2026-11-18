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
 * Pure mapping from overlay-local touch coordinates to normalized `[0..1]` coordinates and engine
 * buffer pixels.
 *
 * Single source of truth:
 * `surfacePoint = overlayPoint - surfaceOffsetRelativeToOverlay`
 * (equivalently `overlayPoint + overlayOffsetRelativeToSurface`),
 * normalized by the engine presentation surface size (`surfaceWidth x surfaceHeight`), and scaled
 * into engine buffer pixels (`bufferWidth x bufferHeight`).
 */
object RustTouchGeometry {
    data class Normalized(val x: Float, val y: Float)

    data class SurfaceGeometry(
        val overlayOffsetX: Int = 0,
        val overlayOffsetY: Int = 0,
        val overlayWidth: Int = 0,
        val overlayHeight: Int = 0,
        val surfaceWidth: Int = 0,
        val surfaceHeight: Int = 0,
        val bufferWidth: Int = 0,
        val bufferHeight: Int = 0
    )

    data class MappedPoint(
        val normX: Float,
        val normY: Float,
        val enginePxX: Float,
        val enginePxY: Float
    )

    fun map(x: Float, y: Float, geometry: SurfaceGeometry): MappedPoint {
        val hasSurface = geometry.surfaceWidth > 0 && geometry.surfaceHeight > 0
        val targetW = (if (hasSurface) geometry.surfaceWidth else geometry.overlayWidth).coerceAtLeast(1).toFloat()
        val targetH = (if (hasSurface) geometry.surfaceHeight else geometry.overlayHeight).coerceAtLeast(1).toFloat()
        val ox = if (hasSurface) geometry.overlayOffsetX else 0
        val oy = if (hasSurface) geometry.overlayOffsetY else 0
        val bufW = (if (geometry.bufferWidth > 0) geometry.bufferWidth else targetW.toInt()).coerceAtLeast(1).toFloat()
        val bufH = (if (geometry.bufferHeight > 0) geometry.bufferHeight else targetH.toInt()).coerceAtLeast(1).toFloat()

        val nx = if (x.isFinite()) ((x + ox) / targetW).coerceIn(0f, 1f) else 0.5f
        val ny = if (y.isFinite()) ((y + oy) / targetH).coerceIn(0f, 1f) else 0.5f
        return MappedPoint(
            normX = nx,
            normY = ny,
            enginePxX = nx * bufW,
            enginePxY = ny * bufH
        )
    }

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
        val mapped = map(
            x = x,
            y = y,
            geometry = SurfaceGeometry(
                overlayOffsetX = offsetX,
                overlayOffsetY = offsetY,
                overlayWidth = areaWidth,
                overlayHeight = areaHeight,
                surfaceWidth = windowWidth,
                surfaceHeight = windowHeight,
                bufferWidth = windowWidth,
                bufferHeight = windowHeight
            )
        )
        return Normalized(mapped.normX, mapped.normY)
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
    @Volatile var overlayOffsetX: Int = 0
    @Volatile var overlayOffsetY: Int = 0
    @Volatile var surfaceWidth: Int = 0
    @Volatile var surfaceHeight: Int = 0
    @Volatile var bufferWidth: Int = 0
    @Volatile var bufferHeight: Int = 0
    @Volatile var overlayWindowType: Int = 0
    @Volatile var overlayZOrderAboveSurface: Boolean = false
    @Volatile var overlayError: String = ""
    @Volatile var lastTouchRawX: Float = Float.NaN
    @Volatile var lastTouchRawY: Float = Float.NaN
    @Volatile var lastMappedX: Float = Float.NaN
    @Volatile var lastMappedY: Float = Float.NaN
    @Volatile var lastMappedPxX: Float = Float.NaN
    @Volatile var lastMappedPxY: Float = Float.NaN
    @Volatile var lastGeometrySnapshot: RustTouchGeometry.SurfaceGeometry =
        RustTouchGeometry.SurfaceGeometry()

    fun reset() {
        touchSeenByActivity.set(0)
        touchSeenByOverlay.set(0)
        touchRoutedToZl2.set(0)
        overlayComposedCount.set(0)
        overlayAttached = false
        overlayVisible = false
        overlayWidth = 0
        overlayHeight = 0
        overlayOffsetX = 0
        overlayOffsetY = 0
        surfaceWidth = 0
        surfaceHeight = 0
        bufferWidth = 0
        bufferHeight = 0
        overlayWindowType = 0
        overlayZOrderAboveSurface = false
        overlayError = ""
        lastTouchRawX = Float.NaN
        lastTouchRawY = Float.NaN
        lastMappedX = Float.NaN
        lastMappedY = Float.NaN
        lastMappedPxX = Float.NaN
        lastMappedPxY = Float.NaN
        lastGeometrySnapshot = RustTouchGeometry.SurfaceGeometry()
    }

    fun recordMappedCursor(
        rawX: Float,
        rawY: Float,
        mapped: RustTouchGeometry.MappedPoint,
        geometry: RustTouchGeometry.SurfaceGeometry
    ) {
        lastGeometrySnapshot = geometry
        lastTouchRawX = rawX
        lastTouchRawY = rawY
        lastMappedX = mapped.normX
        lastMappedY = mapped.normY
        lastMappedPxX = mapped.enginePxX
        lastMappedPxY = mapped.enginePxY
    }

    fun computeRawVsMappedCheck(): String {
        if (!lastTouchRawX.isFinite() || !lastTouchRawY.isFinite() ||
            !lastMappedX.isFinite() || !lastMappedY.isFinite()
        ) {
            return "none"
        }
        val expected = RustTouchGeometry.map(lastTouchRawX, lastTouchRawY, lastGeometrySnapshot)
        val bw = lastGeometrySnapshot.bufferWidth
            .coerceAtLeast(lastGeometrySnapshot.surfaceWidth)
            .coerceAtLeast(1)
        val bh = lastGeometrySnapshot.bufferHeight
            .coerceAtLeast(lastGeometrySnapshot.surfaceHeight)
            .coerceAtLeast(1)
        val dx = kotlin.math.abs(expected.normX - lastMappedX) * bw
        val dy = kotlin.math.abs(expected.normY - lastMappedY) * bh
        val delta = maxOf(dx, dy)
        return if (delta <= 0.5f) {
            "ok(delta=${fmt(delta)}px)"
        } else {
            "mismatch(delta=${fmt(delta)}px expected=${fmt4(expected.normX)},${fmt4(expected.normY)})"
        }
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
            "overlay_offset=${overlayOffsetX},$overlayOffsetY " +
            "surface_size=${surfaceWidth}x$surfaceHeight " +
            "overlay_zorder_above_surface=$overlayZOrderAboveSurface (window_type=$overlayWindowType) " +
            "overlay_composed_count=${overlayComposedCount.get()} " +
            "last_touch_xy_raw=${fmt(lastTouchRawX)},${fmt(lastTouchRawY)} " +
            "last_touch_xy_mapped=${fmt4(lastMappedX)},${fmt4(lastMappedY)} " +
            "mapped_px=${fmt(lastMappedPxX)},${fmt(lastMappedPxY)} " +
            "raw_vs_mapped_check=${computeRawVsMappedCheck()}" +
            if (overlayError.isNotBlank()) " overlay_error=$overlayError" else ""

    private val RAW_TOUCH_REGEX = Regex("\"raw_touch_ignored\"\\s*:\\s*(\\d+)")

    fun parseNativeRawTouch(nativeDiagnostics: String): Long =
        RAW_TOUCH_REGEX.find(nativeDiagnostics)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
}
