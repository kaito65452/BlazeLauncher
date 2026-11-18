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

package io.github.kaito65452.blazelauncher.rustruntime

import io.github.kaito65452.blazelauncher.rustruntime.input.ImeSource
import io.github.kaito65452.blazelauncher.rustruntime.input.RustImeController
import io.github.kaito65452.blazelauncher.rustruntime.input.RustImeDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StageLB4ImeControllerTest {
    private var shows = 0
    private var hides = 0
    private lateinit var c: RustImeController

    @Before
    fun setUp() {
        RustImeDiagnostics.reset()
        shows = 0
        hides = 0
        c = RustImeController(object : RustImeController.Host {
            override fun showIme() { shows++ }
            override fun hideIme() { hides++ }
        })
    }

    @Test
    fun noInitialShowOnTitleOrLoading() {
        c.onOtherRequest(ImeSource.ACTIVITY_CREATE)
        c.onOtherRequest(ImeSource.OVERLAY_ATTACH)
        c.onOtherRequest(ImeSource.WINDOW_FOCUS)
        c.onOtherRequest(ImeSource.RESUME)
        c.onEngineSignal(false)
        assertEquals(0, shows)
        assertFalse(c.wanted)
        assertEquals(4L, RustImeDiagnostics.ignoredShowRequests.get())
    }

    @Test
    fun engineTransitionsShowOnceAndHide() {
        c.onEngineSignal(true)
        c.onEngineSignal(true)
        assertEquals(1, shows)
        c.onEngineSignal(false)
        assertEquals(1, hides)
        assertFalse(c.wanted)
    }

    @Test
    fun userDismissIsRespectedUntilNextTransition() {
        c.onEngineSignal(true)
        c.onImeVisibility(true)
        c.onImeVisibility(false) // Gboard hide button
        assertFalse(c.wanted)
        c.onOtherRequest(ImeSource.WINDOW_FOCUS)
        c.onOtherRequest(ImeSource.RESUME)
        c.onEngineSignal(true) // repeated true
        assertEquals(1, shows)
        c.onEngineSignal(false)
        c.onEngineSignal(true) // next false->true
        assertEquals(2, shows)
    }

    @Test
    fun initialInvisibleInsetsDoNotCountAsDismissal() {
        c.onEngineSignal(true)
        c.onImeVisibility(false) // before the keyboard ever appeared
        assertTrue(c.wanted)
    }

    @Test
    fun manualButtonToggles() {
        c.onManualToggle()
        assertEquals(1, shows)
        c.onManualToggle()
        assertEquals(1, hides)
        assertFalse(c.wanted)
    }

    @Test
    fun chatTapShowsOnlyWhenEngineWantsText() {
        c.onChatTap()
        assertEquals(0, shows)
        c.onEngineSignal(true)
        c.onImeVisibility(true)
        c.onImeVisibility(false)
        c.onChatTap()
        assertEquals(2, shows)
    }

    @Test
    fun ringBufferKeepsLast30() {
        repeat(50) { c.onOtherRequest(ImeSource.RESUME) }
        assertEquals(30, RustImeDiagnostics.transitions().size)
        assertTrue(RustImeDiagnostics.format().contains("ime_show_ignored=50"))
    }
}
