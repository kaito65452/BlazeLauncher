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
import android.util.Log
import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.AccountsManager
import com.movtery.zalithlauncher.game.account.isMicrosoftAccount
import dev.horizon.android.bridge.BlazeNativeBridge
import io.github.kaito65452.blazelauncher.msauth.redactSensitiveData
import org.json.JSONObject
import java.io.File

data class SessionHandoffResult(
    val accepted: Boolean,
    val summary: String
)

/**
 * Hands over an authenticated ZL2 Microsoft account session to Starling via
 * [BlazeNativeBridge.setSession], never starting Starling's own OAuth flow and
 * never writing raw tokens to logs or diagnostic reports.
 */
object RustSessionHandoff {
    private const val TAG = "BlazeRustSpike"
    private const val PENDING_SESSION_FILE = "rust_pending_session.json"
    private val HEX_UUID_32_REGEX = Regex("^[0-9a-f]{32}$")

    fun normalizeUuidOrNull(rawUuid: String?): String? {
        val hex = rawUuid?.trim()?.replace("-", "")?.lowercase() ?: return null
        return hex.takeIf { HEX_UUID_32_REGEX.matches(it) }
    }

    fun isValidPlayerName(name: String?): Boolean {
        val trimmed = name?.trim().orEmpty()
        return trimmed.isNotEmpty() && trimmed.length <= 16
    }

    fun handoffSession(
        user: String,
        uuid: String,
        accessToken: String,
        bridge: (user: String, uuid: String, accessToken: String) -> Boolean = BlazeNativeBridge::setSession
    ): SessionHandoffResult {
        val trimmedUser = user.trim()
        if (!isValidPlayerName(trimmedUser)) {
            return SessionHandoffResult(false, "Rejected: invalid profile length")
        }
        val normalizedUuid = normalizeUuidOrNull(uuid)
            ?: return SessionHandoffResult(false, "Rejected: invalid UUID format")
        if (accessToken.isBlank() || accessToken == "0") {
            return SessionHandoffResult(false, "Rejected: missing Microsoft token")
        }

        val accepted = try {
            bridge(trimmedUser, normalizedUuid, accessToken)
        } catch (t: Throwable) {
            val redactedError = redactSensitiveData(
                "${t.javaClass.simpleName}: ${t.message}"
            )
            return SessionHandoffResult(false, "Bridge call failed ($redactedError)")
        }
        val status = if (accepted) {
            "Accepted in-memory handoff (uuidPrefix=${normalizedUuid.take(8)}..., tokenLen=${accessToken.length})"
        } else {
            "Native bridge rejected session handoff (uuidPrefix=${normalizedUuid.take(8)}...)"
        }
        return SessionHandoffResult(accepted, redactSensitiveData(status))
    }

    fun handoffAccount(
        account: Account?,
        bridge: (user: String, uuid: String, accessToken: String) -> Boolean = BlazeNativeBridge::setSession
    ): SessionHandoffResult {
        if (account == null) {
            return SessionHandoffResult(false, "Skipped: no active ZL2 account selected")
        }
        if (!account.isMicrosoftAccount()) {
            return SessionHandoffResult(false, "Skipped: active ZL2 account is not Microsoft")
        }
        if (account.accessToken.isBlank() || account.accessToken == "0") {
            return SessionHandoffResult(false, "Skipped: Microsoft session has no live token")
        }
        return handoffSession(account.username, account.profileId, account.accessToken, bridge)
    }

    fun prepareSpikeLaunch(context: Context) {
        val target = pendingSessionFile(context)
        val account = runCatching { AccountsManager.currentAccountFlow.value }.getOrNull()
        if (account == null || !account.isMicrosoftAccount() ||
            account.accessToken.isBlank() || account.accessToken == "0" ||
            !isValidPlayerName(account.username)
        ) {
            target.delete()
            return
        }
        val normalizedUuid = normalizeUuidOrNull(account.profileId) ?: run {
            target.delete()
            return
        }
        val payload = JSONObject().apply {
            put("name", account.username.trim())
            put("uuid", normalizedUuid)
            put("access_token", account.accessToken)
        }.toString()

        runCatching {
            val partial = File(target.parentFile, "$PENDING_SESSION_FILE.tmp")
            partial.writeText(payload)
            partial.setReadable(false, false)
            partial.setReadable(true, true)
            partial.setWritable(false, false)
            partial.setWritable(true, true)
            if (!partial.renameTo(target)) {
                target.writeText(payload)
                target.setReadable(false, false)
                target.setReadable(true, true)
                partial.delete()
            }
        }.onFailure { err ->
            target.delete()
            Log.w(
                TAG,
                redactSensitiveData(
                    "Could not stage Microsoft session for :rust: ${err.message}"
                )
            )
        }
    }

    fun consumePendingSessionInRustProcess(
        context: Context,
        bridge: (user: String, uuid: String, accessToken: String) -> Boolean = BlazeNativeBridge::setSession
    ): SessionHandoffResult {
        val target = pendingSessionFile(context)
        if (!target.isFile) {
            return SessionHandoffResult(
                false,
                "No staged ZL2 Microsoft session (Starling OAuth not invoked)"
            )
        }
        val raw = try {
            target.readText()
        } catch (t: Throwable) {
            return SessionHandoffResult(
                false,
                redactSensitiveData("Could not read staged session: ${t.message}")
            )
        } finally {
            target.delete()
        }

        val parsed = runCatching { JSONObject(raw) }.getOrNull()
            ?: return SessionHandoffResult(false, "Rejected: malformed staged session JSON")
        val name = parsed.optString("name", "")
        val uuid = parsed.optString("uuid", "")
        val token = parsed.optString("access_token", "")
        val result = handoffSession(name, uuid, token, bridge)
        Log.i(TAG, "Session handoff outcome: ${result.summary}")
        return result
    }

    private fun pendingSessionFile(context: Context): File {
        val dir = runCatching { context.noBackupFilesDir }.getOrNull() ?: context.filesDir
        return File(dir, PENDING_SESSION_FILE)
    }
}
