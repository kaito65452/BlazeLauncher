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

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.isMicrosoftAccount
import com.movtery.zalithlauncher.game.version.installed.GameRuntime
import com.movtery.zalithlauncher.game.version.installed.Version

sealed interface RustLaunchPrecondition {
    data object Ready : RustLaunchPrecondition
    data class Denied(val reason: String, val requiresOnlineVerification: Boolean = false) : RustLaunchPrecondition
}

/**
 * Enforces Stage D launch invariants for versions whose runtime is [GameRuntime.RUST] and
 * hands verified Microsoft credentials to the isolated `:rust` process hosting [RustActivity].
 */
object RustLaunchDispatcher {
    fun evaluatePreconditions(
        runtime: GameRuntime,
        offlineAccountLogin: Boolean,
        isMicrosoftAccount: Boolean,
        hasLiveToken: Boolean,
        hasNetwork: Boolean,
        launchCheckNeeded: Boolean,
        isEngineDataPrepared: Boolean
    ): RustLaunchPrecondition {
        if (runtime != GameRuntime.RUST) {
            return RustLaunchPrecondition.Denied("Version runtime is not Rust Edition")
        }
        if (offlineAccountLogin || !isMicrosoftAccount || !hasLiveToken) {
            return RustLaunchPrecondition.Denied(
                "Rust Edition requires a verified Microsoft account (offline/local accounts are disabled)"
            )
        }
        if (launchCheckNeeded && !hasNetwork) {
            return RustLaunchPrecondition.Denied(
                "Internet connection is required to verify your Microsoft account before launching Rust Edition",
                requiresOnlineVerification = true
            )
        }
        if (!isEngineDataPrepared) {
            return RustLaunchPrecondition.Denied(
                "Rust Edition engine data is not prepared on this device"
            )
        }
        return RustLaunchPrecondition.Ready
    }

    fun evaluateVersionAccount(
        version: Version,
        account: Account?,
        hasNetwork: Boolean,
        launchCheckNeeded: Boolean,
        isEngineDataPrepared: Boolean
    ): RustLaunchPrecondition {
        val isMs = account?.isMicrosoftAccount() == true
        val liveToken = account != null && account.accessToken.isNotBlank() && account.accessToken != "0"
        return evaluatePreconditions(
            runtime = version.getRuntime(),
            offlineAccountLogin = version.offlineAccountLogin,
            isMicrosoftAccount = isMs,
            hasLiveToken = liveToken,
            hasNetwork = hasNetwork,
            launchCheckNeeded = launchCheckNeeded,
            isEngineDataPrepared = isEngineDataPrepared
        )
    }

    fun launchRustVersion(
        context: Context,
        version: Version,
        account: Account
    ) {
        check(version.getRuntime() == GameRuntime.RUST) {
            "Expected GameRuntime.RUST for ${version.getVersionName()}, got ${version.getRuntime()}"
        }
        check(!version.offlineAccountLogin && account.isMicrosoftAccount()) {
            "Rust Edition cannot launch without a verified Microsoft account."
        }
        check(EngineDataPreparer.isPreparedFallback(context.filesDir)) {
            "Rust Edition engine data is missing on disk."
        }

        val staged = RustSessionHandoff.prepareAccountLaunch(context, account)
        check(staged.accepted) { staged.summary }

        RustDiagnosticsCollector.recordRustEvent(
            context,
            "stage_d_launch version=${version.getVersionName()} ${staged.summary}"
        )
        val intent = Intent(context, RustActivity::class.java).apply {
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
    }
}
