package io.appium.espressoserver.test.http

import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import io.appium.espressoserver.lib.http.ServerBase
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.SocketException
import java.nio.charset.Charset

class RequestBodyTest {
    private val unicode = "Главная Ёжик 🧪 — café 中文"
    private val json = """{"matcher":{"name":"withText","args":["$unicode"]},"itemMatcher":{"name":"hasDescendant","args":[{"name":"withText","args":["$unicode"]}]},"condition":{"name":"withText","args":["$unicode"]},"text":"$unicode"}"""

    @Test
    fun jsonWithoutCharsetPreservesUnicode() {
        assertJsonRoundTrip("application/json")
    }

    @Test
    fun jsonWithExplicitUtf8PreservesUnicode() {
        assertJsonRoundTrip("application/json; charset=UTF-8")
    }

    @Test
    fun jsonMediaTypeIsCaseInsensitive() {
        assertJsonRoundTrip("Application/JSON")
    }

    @Test
    fun explicitCharsetIsPreserved() {
        val body = """{"text":"café"}"""
        val contentType = "application/json; charset=ISO-8859-1"
        val result = parse(contentType, body, Charsets.ISO_8859_1)
        assertEquals(contentType, result.contentType)
        assertEquals(body, result.files["postData"])
    }

    @Test
    fun nonJsonBodyIsUnchanged() {
        val contentType = "application/x-www-form-urlencoded"
        val result = parse(contentType, "text=%D0%93%D0%BB%D0%B0%D0%B2%D0%BD%D0%B0%D1%8F")
        assertEquals(contentType, result.contentType)
        assertEquals(listOf("Главная"), result.parameters["text"])
    }

    private fun assertJsonRoundTrip(contentType: String) {
        val result = parse(contentType, json)
        assertEquals(JsonParser.parseString(json), JsonParser.parseString(result.files.getValue("postData")))
    }

    private data class ParsedRequest(
        val files: Map<String, String>,
        val parameters: Map<String, List<String>>,
        val contentType: String?,
    )

    // Exercise NanoHTTPD's actual header/body decoder without opening a socket or starting Android.
    private fun parse(contentType: String, body: String, charset: Charset = Charsets.UTF_8): ParsedRequest {
        var result: ParsedRequest? = null
        val server = object : ServerBase() {
            override fun advanceComposeMainClockIfNeeded() = Unit

            override fun serve(session: IHTTPSession): Response {
                val files = linkedMapOf<String, String>()
                parseRequestBody(session, files)
                result = ParsedRequest(files, session.parameters.toMap(), session.headers["content-type"])
                return newFixedLengthResponse("ok")
            }

            fun execute(input: ByteArrayInputStream) {
                val session = HTTPSession(DefaultTempFileManager(), input, ByteArrayOutputStream())
                try {
                    session.execute()
                } catch (e: SocketException) {
                    // NanoHTTPD signals a normal Connection: close with this exception.
                    if (e.message != "NanoHttpd Shutdown") throw e
                }
            }
        }
        val bytes = body.toByteArray(charset)
        val header = "POST /probe HTTP/1.1\r\nHost: localhost\r\n" +
            "Content-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        val input = ByteArrayInputStream(header.toByteArray(Charsets.US_ASCII) + bytes)
        server.execute(input)
        return checkNotNull(result) { "NanoHTTPD did not serve the request" }
    }
}
