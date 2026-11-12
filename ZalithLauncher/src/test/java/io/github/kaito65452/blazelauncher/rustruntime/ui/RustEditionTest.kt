package io.github.kaito65452.blazelauncher.rustruntime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RustEditionTest {
    @Test
    fun testRustEditionNavKeyProperties() {
        val key = RustEditionNavKey
        assertNotNull(key.title)
    }

    @Test
    fun testRustEditionInitialUiState() {
        val state = RustEditionUiState()
        assertEquals("not installed", state.engineStatus)
        assertTrue("Supported versions must be initially empty", state.supportedVersions.isEmpty())
        assertFalse("Download assets button must be initially disabled", state.canDownloadAssets)
        assertFalse("Launch button must be initially disabled", state.canLaunch)
    }

    @Test
    fun testCustomUiState() {
        val state = RustEditionUiState(
            engineStatus = "ready",
            supportedVersions = listOf("26.3"),
            canDownloadAssets = true,
            canLaunch = true
        )
        assertEquals("ready", state.engineStatus)
        assertEquals(1, state.supportedVersions.size)
        assertEquals("26.3", state.supportedVersions.first())
        assertTrue(state.canDownloadAssets)
        assertTrue(state.canLaunch)
    }
}
