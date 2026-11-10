package io.appium.espressoserver.test.ultron

import androidx.test.core.app.ActivityScenario
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.appium.espressoserver.lib.drivers.DriverContext
import io.appium.espressoserver.lib.drivers.composeTestRule
import io.appium.espressoserver.lib.handlers.UltronComposeOperation
import io.appium.espressoserver.lib.handlers.UltronComposeParams
import io.appium.espressoserver.lib.handlers.exceptions.InvalidArgumentException
import io.appium.espressoserver.lib.http.Server
import io.appium.espressoserver.lib.http.DEFAULT_PORT
import io.appium.espressoserver.lib.llm.UltronSnapshotResult
import io.appium.espressoserver.lib.llm.UltronSnapshots
import io.appium.espressoserver.lib.model.GlobalSession
import io.appium.espressoserver.lib.model.SessionParams
import org.junit.*
import org.junit.Assert.*
import java.net.Socket

class ComposeOperationTest {
    // The production server rule owns the only Compose test lifecycle.
    @get:Rule val server = Server()
    private lateinit var scenario: ActivityScenario<ComposeOperationActivity>
    private lateinit var activity: ComposeOperationActivity

    @Before fun setup() {
        scenario = ActivityScenario.launch(ComposeOperationActivity::class.java)
        scenario.onActivity { activity = it }
        UltronSnapshots.setEnabled(true)
        DriverContext.composeTestRule.waitForIdle()
    }

    @After fun cleanup() {
        server.stop()
        GlobalSession.delete()
        UltronSnapshots.reset()
        scenario.close()
    }

    private fun run(tag: String, assertion: Boolean = false, extra: String = ""): UltronSnapshotResult {
        val body = """{"matcher":{"name":"hasTestTag","args":["$tag"]},"timeoutMs":0$extra}"""
        val result = UltronComposeOperation(assertion).handleInternal(Gson().fromJson(body, UltronComposeParams::class.java))
        return result
    }
    private fun success(result: UltronSnapshotResult) {
        assertEquals(result.operation.error, "success", result.operation.status)
        assertEquals("compose", result.operation.backend)
        assertEquals(result.snapshot.errors.toString(), "captured", result.snapshot.status)
        assertSame(result, UltronSnapshots.listener.latest)
    }

    @Test fun clicksTextImeAndFailureEvidenceUseComposeWithoutSwitchingDriver() {
        success(run("button", extra = ""","action":"click""""))
        assertEquals(1, activity.clicks)
        success(run("input", extra = ""","action":"replaceText","text":"Hello""""))
        success(run("input", true, ""","assertion":"textEquals","expected":["Hello"]"""))
        success(run("input", extra = ""","action":"imeAction""""))
        assertEquals(1, activity.imeCalls)
        val failure = run("button", true, ""","assertion":"textEquals","expected":["Wrong"]""")
        assertEquals("failure", failure.operation.status)
        assertTrue(failure.snapshot.nodeCount > 0)
        assertSame(failure, UltronSnapshots.listener.latest)
        val ambiguous = run("duplicate", extra = ""","action":"click"""" )
        assertEquals("failure", ambiguous.operation.status)
        assertEquals(1, activity.clicks)
    }

    @Test fun lazyScrollSearchFindsNodesThatWereNotComposedInTheInitialTree() {
        success(run("row-95", true, ""","assertion":"doesNotExist""""))
        success(run("list", extra = ""","action":"scrollToIndex","options":{"index":95}"""))
        success(run("row-95", true, ""","assertion":"isDisplayed""""))
        success(run("list", extra = ""","action":"scrollToKey","options":{"key":"row-10"}"""))
        success(run("row-10", true, ""","assertion":"isDisplayed""""))
        success(run("list", extra = ""","action":"scrollToNode","itemMatcher":{"name":"hasText","args":["Row 110"]}"""))
        success(run("row-110", true, ""","assertion":"isDisplayed""""))
    }

    @Test fun semanticsActionsStateAndStructuredProgressAreExposed() {
        for (check in listOf("isSelected", "isSelectable", "isToggleable", "isIndeterminate", "hasNoClickAction")) {
            success(run("controls", true, ""","assertion":"$check""""))
        }
        success(run("controls", true, ""","assertion":"valueEquals","text":"Mixed""""))
        val changed = run("controls", extra = ""","action":"setProgress","options":{"value":7}""")
        success(changed)
        assertTrue(Gson().toJson(changed.snapshot).contains("\"current\":7.0"))
        success(run("controls", true, ""","assertion":"widthIsEqualTo","options":{"dp":80}"""))
        success(run("controls", true, ""","assertion":"heightIsAtLeast","options":{"dp":40}"""))
        val result = run("controls", true, ""","assertion":"rangeInfoEquals","options":{"current":7,"min":0,"max":10,"steps":9}""")
        success(result)
        assertTrue(Gson().toJson(result.snapshot).contains("\"current\":7.0"))
        for (action in listOf("expand", "collapse", "dismiss")) success(run("controls", extra = ""","action":"$action""""))
        success(run("controls", extra = ""","action":"customAccessibilityAction","options":{"label":"Bookmark"}"""))
        assertEquals(listOf(1, 1, 1, 1), listOf(activity.expandCalls, activity.collapseCalls, activity.dismissCalls, activity.customCalls))
        assertEquals("failure", run("controls", extra = ""","action":"customAccessibilityAction","options":{"label":"Missing"}""").operation.status)
    }

    @Test fun mergedTreesAndCompositeMatchersMatchThePostOperationSnapshot() {
        val body = """{"matcher":{"name":"allOf","args":[{"name":"hasTestTag","args":["merged"]},{"name":"hasAnyDescendant","args":[{"name":"hasText","args":["Alpha"]}]}]},"assertion":"exists","timeoutMs":0}"""
        success(UltronComposeOperation(true).handleInternal(Gson().fromJson(body, UltronComposeParams::class.java)))
        val merged = run("merged", true, ""","assertion":"textEquals","expected":["Alpha","Beta"],"useUnmergedTree":false""")
        success(merged)
        assertTrue(Gson().toJson(merged.snapshot).contains("\"semantics_tree\":\"merged\""))
        assertFalse(Gson().toJson(merged.snapshot).contains("\"test_tag\":\"alpha\""))
        success(run("alpha", true)) // Per-command tree selection must not leak.
    }

    @Test fun textSelectionAndClipboardPreserveSelectionRanges() {
        success(run("input", extra = ""","action":"replaceText","text":"Hello world""""))
        success(run("input", extra = ""","action":"selectText","options":{"start":0,"end":5}"""))
        val selected = run("input", true, ""","assertion":"matches","condition":{"name":"propertyEquals","args":["TextSelectionRange",{"start":0,"end":5}]}""")
        success(selected)
        success(run("input", extra = ""","action":"copyText""""))
        success(run("input", extra = ""","action":"setSelection","options":{"start":11,"end":11,"traversalMode":true}"""))
        success(run("input", extra = ""","action":"pasteText""""))
        success(run("input", true, ""","assertion":"textEquals","expected":["Hello worldHello"]"""))
    }

    @Test fun invalidOptionsAndMatchersAreRejectedBeforeAnyMutation() {
        for (extra in listOf(
            ""","action":"click","options":{"index":0}""",
            ""","action":"scrollToIndex","options":{"index":-1}""",
            ""","action":"setProgress","options":{"value":"7"}""",
            ""","action":"scrollToKey","options":{"keyType":"long","key":"9223372036854775808"}""",
            ""","action":"setSelection","options":{"start":0,"end":1}""",
        )) assertThrows(InvalidArgumentException::class.java) { run("button", extra = extra) }
        assertEquals(0, activity.clicks)
    }

    @Test fun assertionsWaitForANewlyCreatedNodeAndKeepTheRequestedName() {
        scenario.onActivity {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ activity.ready = true }, 150)
        }
        val body = """{"matcher":{"name":"hasTestTag","args":["ready"]},"assertion":"isDisplayed","timeoutMs":3000,"name":"Loaded result"}"""
        val result = UltronComposeOperation(true).handleInternal(Gson().fromJson(body, UltronComposeParams::class.java))
        success(result)
        assertTrue(result.operation.name.contains("Loaded result"))
        assertTrue(Gson().toJson(result.snapshot).contains("\"test_tag\":\"ready\""))
    }

    @Test fun snapshotAssertionErrorsDoNotReplaceASuccessfulComposeAction() {
        val broken = io.appium.espressoserver.lib.llm.UltronSnapshotListener { throw AssertionError("snapshot failed") }
        com.atiurin.ultron.core.config.UltronCommonConfig.addListener(broken)
        try {
            success(run("button", extra = ""","action":"click""""))
            assertEquals(1, activity.clicks)
            assertEquals("success", broken.latest!!.operation.status)
            assertEquals("unavailable", broken.latest!!.snapshot.status)
        } finally { com.atiurin.ultron.core.config.UltronCommonConfig.removeListener(broken.id) }
    }

    @Test fun httpRoutesReturnComposeResultsAndSaveTheLastFailure() {
        GlobalSession.create(SessionParams.W3CCapabilities(emptyList(), emptyMap()))
        server.start()
        val session = GlobalSession.sessionId!!
        fun request(command: String, body: String?, method: String = "POST"): JsonObject = Socket("127.0.0.1", DEFAULT_PORT).use { socket ->
            socket.soTimeout = 10000
            val bytes = (body ?: "").toByteArray()
            val header = "$method /session/$session/appium/execute_mobile/$command HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(header.toByteArray()); write(bytes); flush() }
            val response = socket.getInputStream().bufferedReader().readText()
            assertTrue(response.take(200), response.startsWith("HTTP/1.1 200"))
            Gson().fromJson(response.substringAfter("\r\n\r\n"), JsonObject::class.java).getAsJsonObject("value")
        }
        val acted = request("ultron_compose_act", """{"matcher":{"name":"hasTestTag","args":["button"]},"action":"click","timeoutMs":0}""")
        assertEquals("success", acted.getAsJsonObject("operation")["status"].asString)
        val failed = request("ultron_compose_assert", """{"matcher":{"name":"hasTestTag","args":["missing"]},"assertion":"exists","timeoutMs":0}""")
        assertEquals("failure", failed.getAsJsonObject("operation")["status"].asString)
        assertEquals(failed, request("ultron_last_result", null, "GET"))
    }
}
