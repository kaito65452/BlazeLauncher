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
import com.movtery.zalithlauncher.game.account.microsoft.MinecraftProfileException
import com.movtery.zalithlauncher.game.account.microsoft.MinecraftProfileException.ExceptionStatus.BLOCKED_IP
import com.movtery.zalithlauncher.game.account.microsoft.XboxLoginException
import com.movtery.zalithlauncher.game.account.microsoft.XboxLoginException.ExceptionStatus.BLOCKED_REGION
import com.movtery.zalithlauncher.game.account.microsoft.models.DeviceCodeResponse
import com.movtery.zalithlauncher.path.GLOBAL_JSON
import com.movtery.zalithlauncher.ui.AndroidStringText
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.Parameters
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class MicrosoftAuthErrorTest {

    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        mockWebServer.close()
    }

    @Test
    fun testRedactSensitiveDataRemovesTokens() {
        val payload = """
            {
                "token_type": "Bearer",
                "access_token": "EwB4A8l6BAAUR7v2SLe...",
                "refresh_token": "MCQrw98xL!9z...",
                "user_code": "ABCDEFGH",
                "device_code": "dev-code-12345",
                "uhs": "1234567890",
                "error": "invalid_request"
            }
        """.trimIndent()

        val sanitized = redactSensitiveData(payload)

        assertFalse("Sanitized payload must not contain plain access token", sanitized.contains("EwB4A8l6BAAUR7v2SLe..."))
        assertFalse("Sanitized payload must not contain plain refresh token", sanitized.contains("MCQrw98xL!9z..."))
        assertFalse("Sanitized payload must not contain plain user code", sanitized.contains("ABCDEFGH"))
        assertFalse("Sanitized payload must not contain plain device code", sanitized.contains("dev-code-12345"))
        assertFalse("Sanitized payload must not contain plain uhs", sanitized.contains("1234567890"))
        assertTrue("Sanitized payload must preserve error tag", sanitized.contains("\"error\": \"invalid_request\""))
        assertTrue("Sanitized payload must include [REDACTED]", sanitized.contains("[REDACTED]"))
    }

    @Test
    fun testRedactBearerTokensAndQueryParams() {
        val header = "Authorization: Bearer secret_access_token_value_999"
        val query = "https://example.com/callback?access_token=secret_abc&refresh_token=secret_def"

        val sanitizedHeader = redactSensitiveData(header)
        val sanitizedQuery = redactSensitiveData(query)

        assertEquals("Authorization: Bearer [REDACTED]", sanitizedHeader)
        assertTrue(sanitizedQuery.contains("access_token=[REDACTED]"))
        assertTrue(sanitizedQuery.contains("refresh_token=[REDACTED]"))

        val xblHeader = "Authorization: XBL3.0 x=1234567890;eyJhbGciOiJSUzI1NiJ9.secret"
        val sanitizedXbl = redactSensitiveData(xblHeader)
        assertFalse(sanitizedXbl.contains("eyJhbGciOiJSUzI1NiJ9.secret"))
        assertTrue(sanitizedXbl.contains("XBL3.0 [REDACTED]"))

        val jsonIdentity = """{"identityToken":"XBL3.0 x=999;secret_jwt","error":"Forbidden"}"""
        val sanitizedJson = redactSensitiveData(jsonIdentity)
        assertFalse(sanitizedJson.contains("secret_jwt"))
        assertTrue(sanitizedJson.contains("\"identityToken\":\"[REDACTED]\""))
    }

    @Test
    fun testExtractDiagnosticHeadersIncludesServerAndCfRayAndDropsSecrets() {
        val headers = Headers.build {
            append("Server", "cloudflare")
            append("CF-Ray", "8abc123def456-SIN")
            append("Content-Type", "application/json")
            append("Authorization", "Bearer super_secret_token")
            append("Set-Cookie", "session=secret_cookie")
        }

        val extracted = extractDiagnosticHeaders(headers)
        assertEquals("cloudflare", extracted["Server"])
        assertEquals("8abc123def456-SIN", extracted["CF-Ray"])
        assertEquals("application/json", extracted["Content-Type"])
        assertFalse(extracted.containsKey("Authorization"))
        assertFalse(extracted.containsKey("Set-Cookie"))
    }

    @Test
    fun testLoginWithXbox403BlockedIpPreservesDiagnosticsAndCopyDetails() = runBlocking {
        val cfBody = """
            {
                "error": "Forbidden",
                "errorMessage": "Access denied from current IP",
                "access_token": "leaked_token_should_redact"
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("Content-Type", "application/json")
                .addHeader("Server", "cloudflare")
                .addHeader("CF-Ray", "901234567890abcd-CGK")
                .body(Buffer().writeUtf8(cfBody))
                .build()
        )

        val client = HttpClient(OkHttp) {
            expectSuccess = true
        }

        val requestUrl = mockWebServer.url("/authentication/login_with_xbox").toString()

        try {
            executeMicrosoftAuthStep(
                step = "Minecraft Services Authentication (login_with_xbox)",
                defaultHost = "api.minecraftservices.com"
            ) {
                try {
                    client.post(requestUrl) {
                        contentType(ContentType.Application.Json)
                        setBody("""{"identityToken":"XBL3.0 x=uhs;token"}""")
                    }
                } catch (e: ResponseException) {
                    if (e.response.status.value == 403) {
                        throw MicrosoftHttpStepException(MinecraftProfileException(BLOCKED_IP), e)
                    }
                    throw e
                }
            }
            fail("Expected MicrosoftLoginException for HTTP 403 on login_with_xbox")
        } catch (e: MicrosoftLoginException) {
            assertEquals("Minecraft Services Authentication (login_with_xbox)", e.step)
            assertEquals(403, e.statusCode)
            assertNotNull(e.host)
            assertEquals("cloudflare", e.responseHeaders["Server"])
            assertEquals("901234567890abcd-CGK", e.responseHeaders["CF-Ray"])
            assertNotNull(e.responseBody)
            assertTrue(e.responseBody!!.contains("Access denied from current IP"))
            assertFalse(e.responseBody!!.contains("leaked_token_should_redact"))
            assertTrue(e.responseBody!!.contains("[REDACTED]"))

            val formatted = e.toFormattedAndroidText() as AndroidStringText.Appended
            val fullText = formatted.texts.joinToString("") { part ->
                when (part) {
                    is AndroidStringText.Text -> part.value
                    is AndroidStringText.StringRes -> "[ResId:${part.key}]"
                    else -> ""
                }
            }
            assertTrue(fullText.contains("[ResId:${R.string.account_logging_blocked_ip}]"))
            assertTrue(fullText.contains("Step: Minecraft Services Authentication (login_with_xbox)"))
            assertTrue(fullText.contains("Host: ${e.host}"))
            assertTrue(fullText.contains("Status: 403"))
            assertTrue(fullText.contains("Server=cloudflare"))
            assertTrue(fullText.contains("CF-Ray=901234567890abcd-CGK"))

            val copyable = e.toCopyableDetails()
            assertTrue(copyable.contains("Step: Minecraft Services Authentication (login_with_xbox)"))
            assertTrue(copyable.contains("Host: ${e.host}"))
            assertTrue(copyable.contains("Status: 403"))
            assertTrue(copyable.contains("Server: cloudflare"))
            assertTrue(copyable.contains("CF-Ray: 901234567890abcd-CGK"))
            assertTrue(copyable.contains("Access denied from current IP"))
            assertFalse(copyable.contains("leaked_token_should_redact"))
        } finally {
            client.close()
        }
    }

    @Test
    fun testXstsXboxLoginExceptionPreservesHttpDiagnostics() = runBlocking {
        val xstsBody = """{"Identity":"0","XErr":2148916235,"Message":"","Redirect":"https://start.ui.xboxlive.com/Blocked"}"""

        mockWebServer.enqueue(
            MockResponse.Builder()
                .code(401)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-MS-Request-Id", "req-xsts-123")
                .body(Buffer().writeUtf8(xstsBody))
                .build()
        )

        val client = HttpClient(OkHttp) {
            expectSuccess = true
        }

        val requestUrl = mockWebServer.url("/xsts/authorize").toString()

        try {
            executeMicrosoftAuthStep("Xbox Security Token Service (XSTS)", defaultHost = "xsts.auth.xboxlive.com") {
                try {
                    client.post(requestUrl) {
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                } catch (e: ClientRequestException) {
                    val rawBody = e.response.bodyAsText()
                    throw MicrosoftHttpStepException(XboxLoginException(BLOCKED_REGION), e, rawBody)
                }
            }
            fail("Expected MicrosoftLoginException for XSTS 401")
        } catch (e: MicrosoftLoginException) {
            assertEquals("Xbox Security Token Service (XSTS)", e.step)
            assertEquals(401, e.statusCode)
            assertEquals("req-xsts-123", e.responseHeaders["X-MS-Request-Id"])
            assertTrue(e.responseBody!!.contains("2148916235"))
        } finally {
            client.close()
        }
    }

    @Test
    fun testDeviceCodeResponseUsesMicrosoftVerificationUri() {
        val rawJson = """
            {
                "user_code": "WXYZ1234",
                "device_code": "secret_device_code",
                "verification_uri": "https://www.microsoft.com/link",
                "expires_in": 900,
                "interval": 5,
                "message": "To sign in, use a web browser to open the page https://www.microsoft.com/link and enter the code WXYZ1234."
            }
        """.trimIndent()

        val parsed = GLOBAL_JSON.decodeFromString<DeviceCodeResponse>(rawJson)
        assertEquals("WXYZ1234", parsed.userCode)
        assertEquals("https://www.microsoft.com/link", parsed.verificationUrl)
        assertEquals(900, parsed.expiresIn)
    }

    @Test
    fun testHttp400BadRequestReproducedAndFormattedWithDebugDetails() = runBlocking {
        val mockResponseJson = """
            {
                "error": "invalid_request",
                "error_description": "AADSTS900144: The request body must contain the following parameter: 'client_id'.",
                "error_codes": [900144],
                "timestamp": "2026-11-12 12:00:00Z"
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse.Builder()
                .code(400)
                .addHeader("Content-Type", "application/json")
                .body(Buffer().writeUtf8(mockResponseJson))
                .build()
        )

        val client = HttpClient(OkHttp) {
            expectSuccess = true
        }

        val requestUrl = mockWebServer.url("/consumers/oauth2/v2.0/devicecode").toString()

        try {
            executeMicrosoftAuthStep("Device Code Request") {
                client.submitForm(
                    url = requestUrl,
                    formParameters = Parameters.build {
                        append("client_id", "")
                        append("scope", "XboxLive.signin offline_access openid profile email")
                    }
                )
            }
            fail("Expected MicrosoftLoginException to be thrown for HTTP 400 Bad Request")
        } catch (e: MicrosoftLoginException) {
            assertEquals("Device Code Request", e.step)
            assertEquals(400, e.statusCode)
            assertNotNull(e.responseBody)
            assertTrue(e.responseBody!!.contains("AADSTS900144"))
            assertTrue(e.responseBody!!.contains("invalid_request"))

            val formattedText = e.toFormattedAndroidText()
            assertTrue("Result must be Appended text", formattedText is AndroidStringText.Appended)

            val appended = formattedText as AndroidStringText.Appended
            val fullText = appended.texts.joinToString("") { part ->
                when (part) {
                    is AndroidStringText.Text -> part.value
                    is AndroidStringText.StringRes -> "[ResId:${part.key}]"
                    else -> ""
                }
            }

            assertTrue("Formatted dialog text must contain [Debug Details]", fullText.contains("[Debug Details]"))
            assertTrue("Formatted dialog text must specify Step", fullText.contains("Step: Device Code Request"))
            assertTrue("Formatted dialog text must specify Status 400", fullText.contains("Status: 400"))
            assertTrue("Formatted dialog text must specify failing response", fullText.contains("AADSTS900144"))
            assertTrue("Formatted dialog text must show client_id hint", fullText.contains("Hint: Microsoft OAuth client ID is missing or invalid. Check MS_CLIENT_ID configuration."))
        } finally {
            client.close()
        }
    }
}
