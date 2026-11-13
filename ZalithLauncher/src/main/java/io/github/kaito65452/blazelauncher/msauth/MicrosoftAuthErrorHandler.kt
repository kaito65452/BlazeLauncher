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
import com.movtery.zalithlauncher.game.account.microsoft.toLocal
import com.movtery.zalithlauncher.ui.AndroidStringText
import com.movtery.zalithlauncher.ui.androidText
import com.movtery.zalithlauncher.ui.buildAppendedText
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.network.toLocal
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import kotlinx.coroutines.CancellationException
import java.net.ConnectException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import com.movtery.zalithlauncher.game.account.microsoft.toLocal as notPurchasedToLocal

private const val TAG = "MicrosoftAuth"
private const val MAX_BODY_LENGTH = 4000

const val COPY_LABEL_MS_AUTH_DETAILS = "BlazeLauncher_MS_AUTH_DETAILS"
const val ZERO_CLIENT_ID = "00000000-0000-0000-0000-000000000000"
const val MSG_MS_LOGIN_NOT_CONFIGURED = "Microsoft login is not configured in this build."

private val DIAGNOSTIC_HEADER_DISPLAY_NAMES = mapOf(
    "server" to "Server",
    "cf-ray" to "CF-Ray",
    "cf-cache-status" to "CF-Cache-Status",
    "cf-mitigated" to "CF-Mitigated",
    "x-cache" to "X-Cache",
    "x-amz-cf-id" to "X-Amz-Cf-Id",
    "x-amz-cf-pop" to "X-Amz-Cf-Pop",
    "x-request-id" to "X-Request-Id",
    "x-ms-request-id" to "X-MS-Request-Id",
    "request-id" to "Request-Id",
    "x-correlation-id" to "X-Correlation-Id",
    "retry-after" to "Retry-After",
    "content-type" to "Content-Type",
    "via" to "Via",
    "x-served-by" to "X-Served-By"
)

private val SENSITIVE_HEADER_NAMES = setOf(
    "authorization",
    "proxy-authorization",
    "cookie",
    "set-cookie",
    "x-api-key"
)

fun isValidOAuthClientId(clientId: String?): Boolean {
    val trimmed = clientId?.trim().orEmpty()
    return trimmed.isNotEmpty() && trimmed != ZERO_CLIENT_ID
}

fun requireConfiguredOAuthClientId(
    clientId: String?,
    step: String = "OAuth Client ID Check"
): String {
    val trimmed = clientId?.trim().orEmpty()
    if (trimmed.isEmpty()) {
        val detail = "BuildKeys.OAUTH_CLIENT_ID is empty."
        Logger.error(TAG, "$MSG_MS_LOGIN_NOT_CONFIGURED ($detail)")
        throw MicrosoftLoginException(
            step = step,
            statusCode = null,
            responseBody = null,
            redactedDetails = detail,
            userMessage = MSG_MS_LOGIN_NOT_CONFIGURED
        )
    }
    if (trimmed == ZERO_CLIENT_ID) {
        val detail = "BuildKeys.OAUTH_CLIENT_ID is placeholder zero GUID ($ZERO_CLIENT_ID)."
        Logger.error(TAG, "$MSG_MS_LOGIN_NOT_CONFIGURED ($detail)")
        throw MicrosoftLoginException(
            step = step,
            statusCode = null,
            responseBody = null,
            redactedDetails = detail,
            userMessage = MSG_MS_LOGIN_NOT_CONFIGURED
        )
    }
    return trimmed
}

/**
 * Sanitizes and redacts sensitive tokens, credentials, and identifiers from log messages
 * and error details to ensure secrets are never leaked in error dialogs, logs, or reports.
 */
fun redactSensitiveData(input: String?): String {
    if (input.isNullOrBlank()) return ""
    var sanitized = input

    // Redact JSON string values for token and credential fields
    val jsonTokenRegex = Regex("""(?i)("(?:access_token|refresh_token|identityToken|xblToken|token|Token|id_token|user_code|device_code|uhs|client_secret|password|Authorization)"\s*:\s*")([^"]*)(")""")
    sanitized = jsonTokenRegex.replace(sanitized, "$1[REDACTED]$3")

    // Redact form-encoded and query parameter tokens
    val formParamRegex = Regex("""(?i)((?:access_token|refresh_token|id_token|device_code|client_secret|password)=)([^&\s]+)""")
    sanitized = formParamRegex.replace(sanitized, "$1[REDACTED]")

    // Redact HTTP Authorization Bearer tokens
    val bearerRegex = Regex("""(?i)Bearer\s+([A-Za-z0-9_\-\.+/=]+)""")
    sanitized = bearerRegex.replace(sanitized, "Bearer [REDACTED]")

    // Redact Xbox Live XBL3.0 tokens
    val xblRegex = Regex("""(?i)XBL3\.0\s+x=[^\s"',]+""")
    sanitized = xblRegex.replace(sanitized, "XBL3.0 [REDACTED]")

    // Redact non-Bearer/non-redacted-XBL3.0 Authorization headers if present in text dumps
    val authHeaderRegex = Regex("""(?im)^(Authorization\s*:)(?![ \t]*(?:Bearer\b|XBL3\.0\s+\[REDACTED\]|\[REDACTED\]))[ \t]*(.+)$""")
    sanitized = authHeaderRegex.replace(sanitized, "$1 [REDACTED]")

    return sanitized
}

/**
 * Extracts Server, CF-Ray, and related diagnostic HTTP response headers, omitting sensitive headers
 * and redacting values via [redactSensitiveData].
 */
fun extractDiagnosticHeaders(headers: Headers): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    headers.entries().forEach { (name, values) ->
        val lower = name.lowercase()
        if (lower in SENSITIVE_HEADER_NAMES) return@forEach
        val canonicalName = DIAGNOSTIC_HEADER_DISPLAY_NAMES[lower]
        val isDiagnostic = canonicalName != null ||
            lower.startsWith("cf-") ||
            lower.startsWith("x-ms-") ||
            lower.startsWith("x-cache")
        if (isDiagnostic && values.isNotEmpty()) {
            result[canonicalName ?: name] = redactSensitiveData(values.joinToString(", "))
        }
    }
    return result
}

private fun truncateBody(body: String): String {
    if (body.length <= MAX_BODY_LENGTH) return body
    return body.take(MAX_BODY_LENGTH) + "\n... [truncated, ${body.length} chars total]"
}

/**
 * Internal carrier thrown inside [executeMicrosoftAuthStep] to preserve both a domain-specific
 * exception (e.g., [MinecraftProfileException] or [XboxLoginException]) and the originating
 * HTTP [ResponseException] plus any already-consumed response body text.
 */
class MicrosoftHttpStepException(
    val localizedError: Throwable,
    val responseException: ResponseException,
    val rawBodyOverride: String? = null
) : RuntimeException(localizedError)

/**
 * Structured exception carrying failing request step, HTTP status code, host, diagnostic headers,
 * and sanitized response body.
 */
class MicrosoftLoginException(
    val step: String,
    val statusCode: Int?,
    val responseBody: String?,
    val redactedDetails: String,
    cause: Throwable? = null,
    val userMessage: String? = null,
    val host: String? = null,
    val responseHeaders: Map<String, String> = emptyMap(),
    val localizedBaseMessage: AndroidStringText? = null
) : Exception(redactedDetails, cause) {

    /**
     * Plain-text diagnostic report suitable for copying to the clipboard from the error dialog.
     */
    fun toCopyableDetails(): String = buildString {
        appendLine("Step: $step")
        appendLine("Host: ${host?.ifBlank { "N/A" } ?: "N/A"}")
        appendLine("Status: ${statusCode ?: "N/A"}")
        if (responseHeaders.isNotEmpty()) {
            appendLine("Headers:")
            responseHeaders.forEach { (name, value) ->
                appendLine("  $name: $value")
            }
        }
        append("Response: ${responseBody?.ifBlank { "N/A" } ?: "N/A"}")
    }

    /**
     * Formats error message for user-facing dialogs including localized error header and debug details.
     */
    fun toFormattedAndroidText(): AndroidStringText {
        if (userMessage != null) {
            return buildAppendedText {
                append(androidText(userMessage))
                append("\n\n")
                append("[Debug Details]\n")
                append("Step: $step\n")
                if (!host.isNullOrBlank()) {
                    append("Host: $host\n")
                }
                append("Detail: $redactedDetails")
            }
        }

        val currentCause = cause
        val baseMessage = localizedBaseMessage ?: when (currentCause) {
            is ResponseException -> currentCause.toLocal()
            is HttpRequestTimeoutException -> androidText(R.string.account_logging_time_out)
            is UnknownHostException, is UnresolvedAddressException -> androidText(R.string.error_network_unreachable)
            is ConnectException -> androidText(R.string.error_connection_failed)
            else -> androidText(R.string.account_logging_in_failed)
        }

        return buildAppendedText {
            append(baseMessage)
            append("\n\n")
            append("[Debug Details]\n")
            append("Step: $step\n")
            append("Host: ${host?.ifBlank { "N/A" } ?: "N/A"}\n")
            append("Status: ${statusCode ?: "N/A"}\n")
            if (responseHeaders.isNotEmpty()) {
                append("Headers: ")
                append(responseHeaders.entries.joinToString("; ") { "${it.key}=${it.value}" })
                append("\n")
            }
            append("Response: ${responseBody?.ifBlank { "N/A" } ?: "N/A"}")

            if (statusCode == 400 && responseBody?.contains("client_id", ignoreCase = true) == true) {
                append("\n\nHint: Microsoft OAuth client ID is missing or invalid. Check MS_CLIENT_ID configuration.")
            }
        }
    }
}

private fun resolveLocalizedMessage(th: Throwable): AndroidStringText? = when (th) {
    is XboxLoginException -> th.toLocal()
    is MinecraftProfileException -> th.toLocal()
    is NotPurchasedMinecraftException -> notPurchasedToLocal()
    else -> null
}

private fun extractHost(responseException: ResponseException?): String? {
    if (responseException == null) return null
    return runCatching { responseException.response.call.request.url.host }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
}

private suspend fun buildFailureException(
    step: String,
    defaultHost: String?,
    cause: Throwable,
    responseException: ResponseException?,
    localizedBaseMessage: AndroidStringText?,
    rawBodyOverride: String? = null
): MicrosoftLoginException {
    val statusCode = responseException?.response?.status?.value
    val host = extractHost(responseException) ?: defaultHost
    val headers = responseException?.let { extractDiagnosticHeaders(it.response.headers) } ?: emptyMap()

    val rawBody = when {
        rawBodyOverride != null -> rawBodyOverride
        responseException != null -> {
            runCatching { responseException.response.bodyAsText() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: responseException.message
        }
        else -> cause.message
    }

    val sanitizedBody = truncateBody(redactSensitiveData(rawBody))
    val details = buildString {
        append("Step: $step | Host: ${host ?: "N/A"} | Status: ${statusCode ?: "N/A"}")
        if (headers.isNotEmpty()) {
            append(" | Headers: ")
            append(headers.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        append(" | Body: ")
        append(sanitizedBody.ifBlank { "N/A" })
    }

    Logger.error(TAG, "Microsoft authentication step failed: $details", cause)

    return MicrosoftLoginException(
        step = step,
        statusCode = statusCode,
        responseBody = sanitizedBody,
        redactedDetails = details,
        cause = cause,
        host = host,
        responseHeaders = headers,
        localizedBaseMessage = localizedBaseMessage
    )
}

/**
 * Executes a Microsoft authentication step, extracting HTTP status code, host, diagnostic headers,
 * and sanitized response body on failure, logging the redacted details, and wrapping in [MicrosoftLoginException].
 */
suspend fun <T> executeMicrosoftAuthStep(
    step: String,
    defaultHost: String? = null,
    block: suspend () -> T
): T {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: MicrosoftLoginException) {
        throw e
    } catch (e: CredentialsExpiredException) {
        throw e
    } catch (e: MicrosoftHttpStepException) {
        val localizedBase = resolveLocalizedMessage(e.localizedError)
        throw buildFailureException(
            step = step,
            defaultHost = defaultHost,
            cause = e.localizedError,
            responseException = e.responseException,
            localizedBaseMessage = localizedBase,
            rawBodyOverride = e.rawBodyOverride
        )
    } catch (e: XboxLoginException) {
        val respEx = e.cause as? ResponseException
        throw buildFailureException(
            step = step,
            defaultHost = defaultHost,
            cause = e,
            responseException = respEx,
            localizedBaseMessage = e.toLocal()
        )
    } catch (e: MinecraftProfileException) {
        val respEx = e.cause as? ResponseException
        throw buildFailureException(
            step = step,
            defaultHost = defaultHost,
            cause = e,
            responseException = respEx,
            localizedBaseMessage = e.toLocal()
        )
    } catch (e: NotPurchasedMinecraftException) {
        val respEx = e.cause as? ResponseException
        throw buildFailureException(
            step = step,
            defaultHost = defaultHost,
            cause = e,
            responseException = respEx,
            localizedBaseMessage = notPurchasedToLocal(),
            rawBodyOverride = "Entitlements response returned no Minecraft ownership items."
        )
    } catch (e: Throwable) {
        val respEx = e as? ResponseException
        throw buildFailureException(
            step = step,
            defaultHost = defaultHost,
            cause = e,
            responseException = respEx,
            localizedBaseMessage = null
        )
    }
}
