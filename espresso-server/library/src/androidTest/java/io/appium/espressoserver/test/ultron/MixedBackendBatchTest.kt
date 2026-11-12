package io.appium.espressoserver.test.ultron

import androidx.test.core.app.ActivityScenario
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.appium.espressoserver.lib.handlers.*
import io.appium.espressoserver.lib.handlers.exceptions.InvalidArgumentException
import io.appium.espressoserver.lib.http.Server
import io.appium.espressoserver.lib.llm.SnapshotCapture
import io.appium.espressoserver.lib.llm.UltronSnapshots
import io.appium.espressoserver.lib.model.GlobalSession
import io.appium.espressoserver.lib.model.SessionParams
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit

class MixedBackendBatchTest {
    private lateinit var scenario: ActivityScenario<MixedBackendActivity>
    private val gson = Gson()
    private val server = Server()
    private val native = """{"class":"ViewMatchers","name":"withId","args":[${MixedBackendActivity.BUTTON}]}"""
    private val webView = """{"class":"ViewMatchers","name":"withId","args":[${MixedBackendActivity.WEB}]}"""
    private val nativeClick = """{"matcher":$native,"action":"click","timeoutMs":0}"""
    private val automatorClick = """{"strategy":"text","locator":"Native","action":"click","timeoutMs":0}"""

    @Before fun setup() {
        scenario = ActivityScenario.launch(MixedBackendActivity::class.java)
        lateinit var activity: MixedBackendActivity
        scenario.onActivity { activity = it }
        assertTrue(activity.loaded.await(15, TimeUnit.SECONDS))
        GlobalSession.create(SessionParams.W3CCapabilities(emptyList(), emptyMap()))
        server.setServerSocketFactory { object : ServerSocket() {
            override fun bind(endpoint: SocketAddress?) = super.bind(InetSocketAddress("127.0.0.1", 0))
        } }
        server.start()
    }

    @After fun cleanup() { server.stop(); GlobalSession.delete(); scenario.close() }

    private fun step(id: String, command: String, params: String) = """{"id":"$id","command":"$command","params":$params}"""
    private fun params(vararg steps: String, version: Int = 2) =
        gson.fromJson("""{"schemaVersion":$version,"steps":[${steps.joinToString(",")}] }""", UltronBatchParams::class.java)
    private fun web(id: String, operation: String, assertion: Boolean = false, extra: String = "") =
        """{"webViewMatcher":$webView,"locator":{"using":"ID","value":"$id"},"${if (assertion) "assertion" else "action"}":"$operation","timeoutMs":0$extra}"""

    @Test fun executesAllThreeBackendsAndAssertionsWithoutCapturingSuccessfulSteps() {
        val batch = UltronBatch(captureDiagnostic = { throw AssertionError("Unexpected capture") })
        val result = batch.handleInternal(params(
            step("espresso", "ultronAct", nativeClick),
            step("native-check", "ultronAssert", """{"matcher":$native,"condition":{"class":"ViewMatchers","name":"isDisplayed"},"timeoutMs":0}"""),
            step("automator", "ultronAutomatorAct", automatorClick),
            step("auto-check", "ultronAutomatorAssert", """{"strategy":"text","locator":"Native","assertion":"enabled","timeoutMs":0}"""),
            step("keys", "ultronWebAct", web("input", "typeText", extra = """, "text":"Mixed"""")),
            step("submit", "ultronWebAct", web("submit", "click")),
            step("web-check", "ultronWebAssert", web("status", "textEquals", true, """, "text":"Mixed"""")),
            step("count", "ultronWebAct", web("count", "text")),
        ))
        assertEquals(result.toString(), "success", result.status)
        assertEquals(2, result.schemaVersion)
        assertTrue(result.steps.all { it.snapshot == null })
        assertEquals("1", result.steps.last().value)
        assertEquals(listOf("espresso", "espresso", "automator", "automator", "web", "web", "web", "web"), result.steps.map { it.operation!!.backend })
        scenario.onActivity { assertEquals(2, it.clicks) }
    }

    @Test fun failureSelectsItsBackendRegardlessOfSessionSettingsAndCapturesExactlyOnce() {
        val failures = listOf(
            "ultronAssert" to """{"matcher":$native,"condition":{"class":"ViewMatchers","name":"withText","args":["Wrong"]},"timeoutMs":0}""",
            "ultronAutomatorAssert" to """{"strategy":"text","locator":"Missing","timeoutMs":0}""",
            "ultronWebAssert" to web("status", "textEquals", true, """, "text":"Wrong""""),
        )
        for (enabled in listOf(false, true)) for ((command, failure) in failures) {
            UltronSnapshots.setEnabled(enabled)
            val mode = if (command.contains("Automator")) "ultron" else "automator"
            SnapshotCapture.setMode(mode)
            var captures = 0
            val result = UltronBatch(captureDiagnostic = { captures++; it.captureSafely() }).handleInternal(params(
                step("first", "ultronAct", nativeClick), step("failed", command, failure),
                step("never", "ultronAutomatorAct", automatorClick),
            ))
            assertEquals(result.toString(), "failure", result.status)
            assertEquals(listOf("success", "failure", "skipped"), result.steps.map { it.status })
            assertEquals("failed", result.failedStepId)
            assertEquals(1, captures)
            val snapshot = result.steps[1].snapshot!!
            assertTrue(snapshot.toString(), snapshot.nodeCount > 0)
            assertEquals(if (command.contains("Automator")) "automator" else "espresso", snapshot.backend)
            if (command.contains("Web")) {
                assertEquals("web", snapshot.context!!["backend"])
                assertTrue(gson.toJson(snapshot).contains("diagnostic_target"))
                assertTrue(gson.toJson(snapshot).contains("dom_status"))
            }
            assertEquals(mode, SnapshotCapture.mode)
            assertEquals(enabled, UltronSnapshots.enabled)
            assertEquals(result.steps[1].operation, UltronSnapshots.listener.latest!!.operation)
        }
        scenario.onActivity { assertEquals(6, it.clicks) }
    }

    @Test fun invalidLaterStepsAndVersionOneRejectBeforeAnyMutation() {
        val bad = listOf(
            "ultronAutomatorAct" to """{"strategy":"text","locator":"Native","action":"unknown"}""",
            "ultronAutomatorAssert" to """{"strategy":"text","locator":"Native","assertion":"textEquals"}""",
            "ultronWebAct" to web("submit", "unknown"),
            "ultronWebAssert" to web("status", "textEquals", true),
            "ultronWebAct" to web("submit", "click", extra = """, "frames":[{"index":-1}]"""),
        )
        for ((command, invalid) in bad) assertThrows(InvalidArgumentException::class.java) {
            UltronBatch().handleInternal(params(step("first", "ultronAct", nativeClick), step("invalid", command, invalid)))
        }
        assertThrows(InvalidArgumentException::class.java) {
            UltronBatch().handleInternal(params(step("first", "ultronAct", nativeClick),
                step("auto", "ultronAutomatorAct", automatorClick), version = 1))
        }
        scenario.onActivity { assertEquals(0, it.clicks) }
    }

    @Test fun retriesRefreshTargetsWithoutReplayingEarlierMutationsOrCapturingAttempts() {
        scenario.onActivity { activity ->
            activity.button.isEnabled = false
            activity.button.postDelayed({ activity.button.isEnabled = true }, 1000)
            activity.webView.evaluateJavascript("setTimeout(function(){document.getElementById('status').textContent='Late'},2500)", null)
        }
        val result = UltronBatch(captureDiagnostic = { throw AssertionError("Unexpected retry capture") }).handleInternal(params(
            step("click-once", "ultronWebAct", web("submit", "click")),
            step("auto-retry", "ultronAutomatorAssert", """{"strategy":"text","locator":"Native","assertion":"enabled","timeoutMs":4000}"""),
            step("web-retry", "ultronWebAssert", web("status", "textEquals", true, """, "text":"Late"""").replace("\"timeoutMs\":0", "\"timeoutMs\":4000")),
            step("count", "ultronWebAct", web("count", "text")),
        ))
        assertEquals(result.steps.map { it.operation }.toString(), "success", result.status)
        assertEquals("1", result.steps.last().value)
        assertTrue(result.steps.all { it.snapshot == null })
    }

    @Test fun absenceFalseGettersAndFramesHaveExplicitSemantics() {
        scenario.onActivity { it.button.isEnabled = false }
        val result = UltronBatch().handleInternal(params(
            step("getter", "ultronAutomatorAct", """{"strategy":"text","locator":"Native","action":"enabled","timeoutMs":0}"""),
            step("assertion", "ultronAutomatorAssert", """{"strategy":"text","locator":"Native","assertion":"enabled","expected":false,"timeoutMs":0}"""),
            step("absent", "ultronAutomatorAssert", """{"strategy":"text","locator":"Missing","assertion":"notExists","timeoutMs":0}"""),
            step("web-absent", "ultronWebAssert", web("missing", "notExists", true)),
            step("frame", "ultronWebAssert", web("inside", "textEquals", true,
                """, "text":"Frame ready", "frames":[{"idOrName":"inner"}]""")),
        ))
        assertEquals(result.toString(), "success", result.status)
        assertEquals(false, result.steps.first().value)
        val missing = UltronBatch().handleInternal(params(step("missing", "ultronAutomatorAct",
            """{"strategy":"text","locator":"Missing","action":"click","timeoutMs":0}""")))
        assertEquals("failure", missing.status)
    }

    @Test fun ambiguousTargetsNeverActAndBrokenCapturePreservesTheOperationFailure() {
        scenario.onActivity {
            (it.button.parent as android.widget.LinearLayout).addView(android.widget.Button(it).apply { text = "Native"; isAllCaps = false })
        }
        val result = UltronBatch(captureDiagnostic = { throw AssertionError("broken dump") }).handleInternal(params(
            step("ambiguous", "ultronAutomatorAct", automatorClick), step("never", "ultronAct", nativeClick)))
        assertEquals("failure", result.status)
        assertEquals("unavailable", result.steps[0].snapshot!!.status)
        assertTrue(result.steps[0].operation!!.error!!.contains("Expected one Automator element"))
        assertFalse(result.steps[0].operation!!.error!!.contains("broken dump"))
        scenario.onActivity { assertEquals(0, it.clicks) }
        val webResult = UltronBatch().handleInternal(params(step("web", "ultronWebAct",
            """{"webViewMatcher":$webView,"locator":{"using":"CSS_SELECTOR","value":".duplicate"},"action":"click","timeoutMs":0}""")))
        assertEquals("failure", webResult.status)
        assertTrue(webResult.steps[0].operation!!.error!!.contains("found 2"))
    }

    @Test fun standaloneHttpCommandsUseTheSameExecutionAndValidateRawJson() {
        fun request(command: String, body: String): JsonObject = Socket("127.0.0.1", server.listeningPort).use { socket ->
            socket.soTimeout = 15000
            val bytes = body.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write("POST /session/${GlobalSession.sessionId}/appium/execute_mobile/$command HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes); flush()
            }
            gson.fromJson(socket.getInputStream().bufferedReader().readText().substringAfter("\r\n\r\n"), JsonObject::class.java).getAsJsonObject("value")
        }
        for ((command, body) in listOf("ultron_automator_act" to automatorClick,
            "ultron_automator_assert" to """{"strategy":"text","locator":"Native","timeoutMs":0}""",
            "ultron_web_act" to web("submit", "click"), "ultron_web_assert" to web("count", "textEquals", true, """, "text":"1""""))) {
            val result = request(command, body)
            assertEquals(result.toString(), "success", result.getAsJsonObject("operation")["status"].asString)
        }
        val invalid = request("ultron_automator_act", automatorClick.replace("\"timeoutMs\":0", "\"timeoutMs\":\"0\""))
        assertTrue(invalid.toString(), invalid.has("error"))
        val batch = request("ultron_batch", """{"schemaVersion":2,"steps":[${step("web", "ultronWebAssert", web("status", "exists", true))}]}""")
        assertEquals(batch.toString(), "success", batch["status"].asString)
        assertEquals(2, batch["schemaVersion"].asInt)
        scenario.onActivity { assertEquals(1, it.clicks) }
    }
}
