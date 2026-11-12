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

import com.movtery.zalithlauncher.ui.AndroidStringText
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.forms.submitForm
import io.ktor.http.Parameters
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
