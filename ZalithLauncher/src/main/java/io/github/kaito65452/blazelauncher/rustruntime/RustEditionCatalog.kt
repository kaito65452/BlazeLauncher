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
import com.google.gson.JsonParser

/**
 * Reads supported Minecraft version metadata from the bundled `research-lock.json` asset
 * (generated from `engine/starling/research-lock.json` at APK build time) without hardcoding
 * engine compatibility outside the lock metadata.
 */
object RustEditionCatalog {
    const val LOCK_ASSET_NAME: String = EngineDataPrepareSupport.LOCK_ASSET_NAME
    const val FALLBACK_VERSION: String = EngineDataPrepareSupport.TARGET_MINECRAFT_VERSION

    @Volatile
    private var cachedVersion: String? = null

    fun parseSupportedVersionFromLockJson(lockJson: String): String? = runCatching {
        val root = JsonParser.parseString(lockJson).asJsonObject
        val minecraft = root.getAsJsonObject("minecraft") ?: return@runCatching null
        minecraft.get("version")?.asString?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun isSupportedVersionMatch(gameVersion: String?, supportedVersion: String?): Boolean {
        val candidate = gameVersion?.trim().orEmpty()
        val expected = supportedVersion?.trim().orEmpty()
        return candidate.isNotEmpty() && candidate == expected
    }

    fun supportedMinecraftVersion(context: Context): String {
        cachedVersion?.let { return it }
        val loaded = runCatching {
            context.assets.open(LOCK_ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrNull()?.let(::parseSupportedVersionFromLockJson) ?: FALLBACK_VERSION
        cachedVersion = loaded
        return loaded
    }

    fun isSupportedGameVersion(context: Context, gameVersion: String?): Boolean =
        isSupportedVersionMatch(gameVersion, supportedMinecraftVersion(context))
}
