/*
 * Blaze Launcher
 * Copyright (C) 2026 BlazeLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package io.github.kaito65452.blazelauncher.msauth

import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.AccountType
import com.movtery.zalithlauncher.game.account.isAuthServerAccount
import com.movtery.zalithlauncher.game.account.isLocalAccount
import com.movtery.zalithlauncher.game.account.isMicrosoftAccount
import com.movtery.zalithlauncher.game.account.microsoft.fetchDeviceCodeResponse
import com.movtery.zalithlauncher.game.account.microsoft.getTokenResponse
import com.movtery.zalithlauncher.game.account.microsoft.models.DeviceCodeResponse
import com.movtery.zalithlauncher.ui.AndroidStringText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

class OAuthClientIdGuardTest {

    private fun AndroidStringText.flattenText(): String = when (this) {
        is AndroidStringText.Text -> value
        is AndroidStringText.Annotated -> value.toString()
        is AndroidStringText.StringRes -> "[ResId:$key]"
        is AndroidStringText.Appended -> texts.joinToString("") { it.flattenText() }
    }

    @Test
    fun testIsValidOAuthClientId() {
        assertFalse(isValidOAuthClientId(null))
        assertFalse(isValidOAuthClientId(""))
        assertFalse(isValidOAuthClientId("   "))
        assertFalse(isValidOAuthClientId(ZERO_CLIENT_ID))
        assertTrue(isValidOAuthClientId("12345678-1234-1234-1234-123456789abc"))
    }

    @Test
    fun testBlankClientIdThrowsNotConfiguredWithoutCallingMicrosoft() {
        try {
            requireConfiguredOAuthClientId("", "Device Code Request")
            fail("Expected MicrosoftLoginException for blank client ID")
        } catch (e: MicrosoftLoginException) {
            val message = e.toFormattedAndroidText().flattenText()
            assertTrue(message.contains(MSG_MS_LOGIN_NOT_CONFIGURED))
            assertTrue(message.contains("[Debug Details]"))
            assertTrue(message.contains("empty"))
        }

        try {
            runBlocking {
                fetchDeviceCodeResponse(EmptyCoroutineContext, clientId = "   ")
            }
            fail("Expected fetchDeviceCodeResponse to reject blank client ID before network call")
        } catch (e: MicrosoftLoginException) {
            assertEquals(MSG_MS_LOGIN_NOT_CONFIGURED, e.userMessage)
            assertTrue(e.toFormattedAndroidText().flattenText().contains(MSG_MS_LOGIN_NOT_CONFIGURED))
        }
    }

    @Test
    fun testZeroGuidClientIdThrowsNotConfiguredWithoutCallingMicrosoft() {
        try {
            requireConfiguredOAuthClientId(ZERO_CLIENT_ID, "Device Code Request")
            fail("Expected MicrosoftLoginException for zero GUID client ID")
        } catch (e: MicrosoftLoginException) {
            val message = e.toFormattedAndroidText().flattenText()
            assertTrue(message.contains(MSG_MS_LOGIN_NOT_CONFIGURED))
            assertTrue(message.contains("[Debug Details]"))
            assertTrue(message.contains(ZERO_CLIENT_ID))
        }

        val dummyCodeResponse = DeviceCodeResponse(
            deviceCode = "dev",
            userCode = "USER",
            verificationUrl = "https://example.com",
            expiresIn = 60,
            interval = 5
        )
        try {
            runBlocking {
                getTokenResponse(dummyCodeResponse, EmptyCoroutineContext, clientId = ZERO_CLIENT_ID) { false }
            }
            fail("Expected getTokenResponse to reject zero GUID before network call")
        } catch (e: MicrosoftLoginException) {
            assertEquals(MSG_MS_LOGIN_NOT_CONFIGURED, e.userMessage)
        }
    }

    @Test
    fun testValidGuidClientIdAcceptedAndTrimmed() {
        val validGuid = "12345678-1234-1234-1234-123456789abc"
        val resolved = requireConfiguredOAuthClientId("  $validGuid  ", "Device Code Request")
        assertEquals(validGuid, resolved)
    }

    @Test
    fun testOfflineAndElyByAccountsUnchanged() {
        val offline = Account(username = "OfflineSteve", accountType = AccountType.LOCAL.tag)
        val elyBy = Account(
            username = "ElySteve",
            accountType = "Ely.by",
            otherAccount = "ely@example.com",
            otherBaseUrl = "https://ely.by"
        )

        assertTrue(offline.isLocalAccount())
        assertFalse(offline.isMicrosoftAccount())
        assertTrue(elyBy.isAuthServerAccount())
        assertFalse(elyBy.isMicrosoftAccount())
    }
}
