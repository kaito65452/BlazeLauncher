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

import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.account.CredentialsExpiredException
import com.movtery.zalithlauncher.game.account.microsoft.MinecraftProfileException
import com.movtery.zalithlauncher.game.account.microsoft.NotPurchasedMinecraftException
import com.movtery.zalithlauncher.game.account.microsoft.XboxLoginException
import com.movtery.zalithlauncher.ui.AndroidStringText
import com.movtery.zalithlauncher.ui.androidText
import com.movtery.zalithlauncher.ui.buildAppendedText
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.network.toLocal
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException

private const val TAG = "MicrosoftAuth"

/**
 * Sanitizes and redacts sensitive tokens, credentials, and identifiers from log messages
 * and error details to ensure secrets are never leaked in error dialogs, logs, or reports.
 */
fun redactSensitiveData(input: String?): String {
    if (input.isNullOrBlank()) return ""
    var sanitized = input

    // Redact JSON string values for token and credential fields
    val jsonTokenRegex = Regex("""(?i)("(?:access_token|refresh_token|token|Token|id_token|user_code|device_code|uhs|client_secret|password)"\s*:\s*")([^"]*)(")""")
    sanitized = jsonTokenRegex.replace(sanitized, "$1[REDACTED]$3")

    // Redact form-encoded and query parameter tokens
    val formParamRegex = Regex("""(?i)((?:access_token|refresh_token|id_token|client_secret|password)=)([^&\s]+)""")
    sanitized = formParamRegex.replace(sanitized, "$1[REDACTED]")

    // Redact HTTP Authorization Bearer tokens
    val bearerRegex = Regex("""(?i)Bearer\s+([A-Za-z0-9_\-\.]+)""")
    sanitized = bearerRegex.replace(sanitized, "Bearer [REDACTED]")

    return sanitized
}

/**
 * Structured exception carrying failing request step, HTTP status code, and sanitized response body.
 */
class MicrosoftLoginException(
    val step: String,
    val statusCode: Int?,
    val responseBody: String?,
    val redactedDetails: String,
    cause: Throwable? = null
) : Exception(redactedDetails, cause) {

    /**
     * Formats error message for user-facing dialogs including localized error header and debug details.
     */
    fun toFormattedAndroidText(): AndroidStringText {
        val currentCause = cause
        val baseMessage = when (currentCause) {
            is ResponseException -> currentCause.toLocal()
            else -> androidText(R.string.account_logging_in_failed)
        }

        return buildAppendedText {
            append(baseMessage)
            append("\n\n")
            append("[Debug Details]\n")
            append("Step: $step\n")
            append("Status: ${statusCode ?: "N/A"}\n")
            append("Response: ${responseBody?.ifBlank { "N/A" } ?: "N/A"}")

            if (statusCode == 400 && responseBody?.contains("client_id", ignoreCase = true) == true) {
                append("\n\nHint: Microsoft OAuth client ID is missing or invalid. Check MS_CLIENT_ID configuration.")
            }
        }
    }
}

/**
 * Executes a Microsoft authentication step, extracting HTTP status code and response body
 * on failure, logging the redacted error to Logger, and wrapping it in a [MicrosoftLoginException].
 */
suspend fun <T> executeMicrosoftAuthStep(step: String, block: suspend () -> T): T {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: MicrosoftLoginException) {
        throw e
    } catch (e: XboxLoginException) {
        throw e
    } catch (e: MinecraftProfileException) {
        throw e
    } catch (e: NotPurchasedMinecraftException) {
        throw e
    } catch (e: CredentialsExpiredException) {
        throw e
    } catch (e: Throwable) {
        val statusCode: Int? = when (e) {
            is ResponseException -> e.response.status.value
            else -> null
        }

        val rawBody: String? = when (e) {
            is ClientRequestException -> {
                runCatching { e.response.bodyAsText() }.getOrNull() ?: e.message
            }
            is ResponseException -> {
                runCatching { e.response.bodyAsText() }.getOrNull() ?: e.message
            }
            else -> e.message
        }

        val sanitizedBody = redactSensitiveData(rawBody)
        val details = buildString {
            append("Step: $step | Status: ${statusCode ?: "N/A"} | Body: ")
            append(sanitizedBody.ifBlank { "N/A" })
        }

        Logger.error(TAG, "Microsoft authentication step failed: $details", e)

        throw MicrosoftLoginException(
            step = step,
            statusCode = statusCode,
            responseBody = sanitizedBody,
            redactedDetails = details,
            cause = e
        )
    }
}
