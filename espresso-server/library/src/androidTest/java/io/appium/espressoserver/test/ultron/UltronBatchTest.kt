package io.appium.espressoserver.test.ultron

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.atiurin.ultron.core.config.UltronCommonConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.appium.espressoserver.lib.handlers.UltronBatch
import io.appium.espressoserver.lib.handlers.UltronBatchParams
import io.appium.espressoserver.lib.http.Server
import io.appium.espressoserver.lib.llm.UltronSnapshotListener
import io.appium.espressoserver.lib.llm.UltronSnapshots
import io.appium.espressoserver.lib.model.GlobalSession
import io.appium.espressoserver.lib.model.SessionParams
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress

class UltronBatchTest {
    private lateinit var scenario: ActivityScenario<UltronActionActivity>
    private val server = Server()
    private val target = """{"class":"ViewMatchers","name":"withId","args":[${UltronActionActivity.BUTTON_ID}]}"""
    private val click = """{"matcher":$target,"action":"click","timeoutMs":0}"""

    @Before fun setup() {
        scenario = ActivityScenario.launch(UltronActionActivity::class.java)
        GlobalSession.create(SessionParams.W3CCapabilities(emptyList(), emptyMap()))
        server.setServerSocketFactory {
            object : ServerSocket() {
                override fun bind(endpoint: SocketAddress?) = super.bind(InetSocketAddress("127.0.0.1", 0))
            }
        }
        server.start()
    }

    @After fun cleanup() {
        server.stop()
        GlobalSession.delete()
        scenario.close()
    }

    private fun step(id: String, command: String, params: String) = """{"id":"$id","command":"$command","params":$params}"""
    private fun request(command: String, body: String?, method: String = "POST"): JsonObject =
        Socket("127.0.0.1", server.listeningPort).use { socket ->
            socket.soTimeout = 15000
            val bytes = (body ?: "").toByteArray(Charsets.UTF_8)
            val header = "$method /session/${GlobalSession.sessionId}/appium/execute_mobile/$command HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(header.toByteArray()); write(bytes); flush() }
            Gson().fromJson(socket.getInputStream().bufferedReader().readText().substringAfter("\r\n\r\n"), JsonObject::class.java)
        }

    @Test fun successNeverCapturesAndFailureCapturesOnceStopsAndPreservesSettings() {
        var automaticCaptures = 0
        val observer = UltronSnapshotListener { automaticCaptures++; throw AssertionError("Unexpected automatic capture") }
        UltronCommonConfig.addListener(observer)
        try {
            for (enabled in listOf(false, true)) {
                UltronSnapshots.setEnabled(enabled)
                val ok = request("ultron_batch", """{"steps":[${step("click", "ultronAct", click)}]}""").getAsJsonObject("value")
                assertEquals(ok.toString(), "success", ok["status"].asString)
                assertTrue(ok.getAsJsonArray("steps")[0].asJsonObject["snapshot"].isJsonNull)
                val failure = """{"matcher":$target,"condition":{"class":"ViewMatchers","name":"withText","args":["Wrong"]},"timeoutMs":0}"""
                val body = """{"steps":[${step("first", "ultronAct", click)},${step("failed", "ultronAssert", failure)},${step("never", "ultronAct", click)}]}"""
                val value = request("ultron_batch", body).getAsJsonObject("value")
                assertEquals(value.toString(), "failure", value["status"].asString)
                assertEquals("failed", value["failedStepId"].asString)
                val results = value.getAsJsonArray("steps").map { it.asJsonObject }
                assertEquals(listOf("success", "failure", "skipped"), results.map { it["status"].asString })
                assertTrue(results[0]["snapshot"].isJsonNull)
                assertTrue(results[2]["snapshot"].isJsonNull)
                assertTrue(results[2]["operation"].isJsonNull)
                assertTrue(results[1].getAsJsonObject("snapshot")["nodeCount"].asInt > 0)
                assertEquals(results[1].getAsJsonObject("operation"), request("ultron_last_result", null, "GET").getAsJsonObject("value").getAsJsonObject("operation"))
                assertEquals(enabled, UltronSnapshots.enabled)
            }
            scenario.onActivity { assertEquals(4, it.clicks) }
            assertEquals(0, automaticCaptures)
        } finally { UltronCommonConfig.removeListener(observer.id) }
        // The enabled session resumes its normal per-command snapshot behavior after the batch.
        assertEquals("captured", request("ultron_act", click).getAsJsonObject("value").getAsJsonObject("snapshot")["status"].asString)
    }

    @Test fun invalidLaterStepsPreventAllMutations() {
        val first = step("first", "ultronAct", click)
        for (later in listOf(
            step("first", "ultronAct", click),
            step("invalid", "ultronAct", """{"matcher":$target,"action":"unknown"}"""),
            step("invalid", "ultronAssert", """{"matcher":{"class":"ViewMatchers","name":"missingFactory"}}"""),
            step("invalid", "ultronAssert", """{"matcher":$target,"exists":"false"}"""),
            step("invalid", "ultronElement", """{"backend":"espresso","using":"css selector","value":"button","action":"click"}"""),
            step("invalid", "ultronElement", """{"backend":"espresso","using":"text","value":"Click","action":"replaceText"}"""),
            step("invalid", "ultronBack", """{"action":"click"}"""),
        )) {
            val response = request("ultron_batch", """{"steps":[$first,$later]}""").getAsJsonObject("value")
            assertTrue(response.toString(), response.has("error"))
        }
        scenario.onActivity { assertEquals(0, it.clicks) }
    }

    @Test fun navigationAndLocatorOperationsReplayWithoutCachedElementIdsAndKeepFalseAsAValue() {
        // Back must dismiss the dialog, rather than a keyboard left open by another test.
        closeSoftKeyboard()
        scenario.onActivity {
            it.button.isEnabled = false
            android.app.AlertDialog.Builder(it).setMessage("Dismiss this dialog").setPositiveButton("OK", null).show()
        }
        onView(withText("Dismiss this dialog")).inRoot(isDialog()).check(matches(isDisplayed()))
        val input = """{"class":"ViewMatchers","name":"withId","args":[${UltronActionActivity.INPUT_ID}]}"""
        fun element(id: String, matcher: String, action: String, text: String? = null): String {
            val args = JsonObject().apply {
                addProperty("backend", "espresso"); addProperty("using", "-android viewmatcher")
                addProperty("value", matcher); addProperty("action", action)
                text?.let { addProperty("text", it) }
            }
            return step(id, "ultronElement", args.toString())
        }
        val body = """{"steps":[${listOf(
            step("back", "ultronBack", "{}"),
            element("input", input, "replaceText", "Recorded"),
            step("hide", "ultronHideKeyboard", "{}"),
            element("text", input, "text"),
            element("clear", input, "clear"),
            element("empty", input, "text"),
            element("disabled", target, "enabled"),
            element("find", input, "find"),
        ).joinToString(",")}]}"""
        val result = request("ultron_batch", body).getAsJsonObject("value")
        assertEquals(result.toString(), "success", result["status"].asString)
        val steps = result.getAsJsonArray("steps").map { it.asJsonObject }
        assertTrue(steps.all { it["snapshot"].isJsonNull })
        assertEquals("Recorded", steps[3]["value"].asString)
        assertEquals("", steps[5]["value"].asString)
        assertFalse(steps[6]["value"].asBoolean)
        assertEquals("success", steps[6]["status"].asString)
        val found = steps[7].getAsJsonObject("element")
        assertTrue(found.has("ELEMENT") || found.has("element-6066-11e4-a52e-4f735466cecf"))
    }

    @Test fun missingElementStopsBeforeNavigationAndCapturesItsFailure() {
        val body = """{"steps":[${step("missing", "ultronElement", """{"backend":"espresso","using":"text","value":"Missing element","action":"click"}""")},${step("never", "ultronBack", "{}")}]}"""
        val result = request("ultron_batch", body).getAsJsonObject("value")
        assertEquals("failure", result["status"].asString)
        val steps = result.getAsJsonArray("steps")
        assertTrue(steps[0].asJsonObject.getAsJsonObject("snapshot")["nodeCount"].asInt > 0)
        assertEquals("skipped", steps[1].asJsonObject["status"].asString)
    }

    @Test fun recordedElementUniquenessGuardStopsBeforeAnyAmbiguousClick() {
        scenario.onActivity { activity ->
            (activity.button.parent as android.widget.LinearLayout).addView(android.widget.Button(activity).apply { text = "Click" })
        }
        val body = """{"steps":[${step("ambiguous", "ultronElement", """{"backend":"espresso","using":"text","value":"Click","action":"click","requireUnique":true}""")},${step("never", "ultronAct", click)}]}"""
        val result = request("ultron_batch", body).getAsJsonObject("value")
        assertEquals("failure", result["status"].asString)
        assertTrue(result.getAsJsonArray("steps")[0].asJsonObject.getAsJsonObject("operation")["error"].asString.contains("found 2"))
        assertEquals("skipped", result.getAsJsonArray("steps")[1].asJsonObject["status"].asString)
        scenario.onActivity { assertEquals(0, it.clicks) }
    }

    @Test fun brokenFailureCaptureDoesNotReplaceTheAssertionFailure() {
        var captures = 0
        val handler = UltronBatch { captures++; throw AssertionError("capture failed") }
        val failure = """{"matcher":$target,"condition":{"class":"ViewMatchers","name":"withText","args":["Wrong"]},"timeoutMs":0}"""
        val params = Gson().fromJson("""{"steps":[${step("failed", "ultronAssert", failure)},${step("never", "ultronAct", click)}]}""", UltronBatchParams::class.java)
        val result = handler.handleInternal(params)
        assertEquals(1, captures)
        assertEquals("failure", result.status)
        assertEquals("unavailable", result.steps[0].snapshot!!.status)
        assertFalse(result.steps[0].operation!!.error!!.contains("capture failed"))
        assertEquals("skipped", result.steps[1].status)
        scenario.onActivity { assertEquals(0, it.clicks) }
    }
}
