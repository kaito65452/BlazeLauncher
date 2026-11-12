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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AccountCompatibilityTest {

    @Test
    fun testOfflineLocalAccountWorksIndependently() {
        val testPlayerName = "Steve"
        val offlineAccount = Account(
            username = testPlayerName,
            accountType = AccountType.LOCAL.tag,
            profileId = UUID.nameUUIDFromBytes("OfflinePlayer:$testPlayerName".toByteArray()).toString().replace("-", "")
        )

        assertTrue("Account must be recognized as local", offlineAccount.isLocalAccount())
        assertFalse("Account must not be marked as Microsoft", offlineAccount.isMicrosoftAccount())
        assertFalse("Account must not be marked as auth server account", offlineAccount.isAuthServerAccount())
        assertEquals("Steve", offlineAccount.username)
        assertTrue("Offline account profile ID must be non-empty", offlineAccount.profileId.isNotBlank())
    }

    @Test
    fun testMicrosoftAccountTagging() {
        val msAccount = Account(
            username = "MojangPlayer",
            accountType = AccountType.MICROSOFT.tag,
            profileId = "0123456789abcdef0123456789abcdef"
        )

        assertTrue("Account must be recognized as Microsoft", msAccount.isMicrosoftAccount())
        assertFalse("Account must not be marked as local", msAccount.isLocalAccount())
    }

    @Test
    fun testElyByAndThirdPartyAuthServerAccountClassification() {
        val elyByAccount = Account(
            username = "ElyPlayer",
            accountType = "Ely.by",
            otherAccount = "player@example.com",
            otherBaseUrl = "https://ely.by",
            profileId = "fedcba9876543210fedcba9876543210"
        )

        assertTrue("Account must be recognized as auth server account", elyByAccount.isAuthServerAccount())
        assertFalse("Ely.by account must not be marked as Microsoft account", elyByAccount.isMicrosoftAccount())
        assertFalse("Ely.by account must not be marked as local account", elyByAccount.isLocalAccount())
        assertEquals("https://ely.by", elyByAccount.otherBaseUrl)
    }
}
