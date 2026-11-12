package io.appium.espressoserver.test.ultron

import android.text.InputType
import androidx.test.core.app.ActivityScenario
import androidx.test.runner.AndroidJUnit4
import com.google.gson.Gson
import io.appium.espressoserver.lib.handlers.UltronLlmOperation
import io.appium.espressoserver.lib.handlers.UltronOperationParams
import io.appium.espressoserver.lib.helpers.InteractionHelper
import io.appium.espressoserver.lib.llm.SnapshotCapture
import io.appium.espressoserver.lib.llm.UltronSnapshots
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class UnifiedSnapshotTest {
    @After fun reset() { UltronSnapshots.reset() }

    @Test fun accessibilityUsesTheCommonEnvelopeAndRedactsPasswords() {
        ActivityScenario.launch(UltronActionActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                it.input.setText("accessibility-secret-572")
            }
            InteractionHelper.getUiDevice().waitForIdle()
            val snapshot = SnapshotCapture.capture(mode = "automator")
            assertEquals(snapshot.errors.toString(), "captured", snapshot.status)
            assertEquals("automator", snapshot.backend)
            assertEquals("automator", snapshot.mode)
            val nodes = flatten(snapshot.roots)
            assertEquals(nodes.size, snapshot.nodeCount)
            val button = nodes.single { it["text"] == "Click" }
            assertEquals("accessibility", button["source"])
            assertEquals(true, button["clickable"])
            assertEquals(setOf("l", "t", "r", "b"), (button["bounds"] as Map<*, *>).keys)
            assertTrue(nodes.any { it["sensitive"] == true && it["text"] == "[REDACTED]" })
            assertFalse(Gson().toJson(snapshot).contains("accessibility-secret-572"))
            assertEquals("ultron", SnapshotCapture.mode) // A per-call override is not a session mutation.
        }
    }

    @Test fun systemUiWindowsAreVisibleWithoutAppiumOrEspressoRootSelection() {
        ActivityScenario.launch(UltronActionActivity::class.java).use {
            val device = InteractionHelper.getUiDevice()
            try {
                assertTrue(device.openNotification())
                device.waitForIdle()
                val snapshot = SnapshotCapture.capture(mode = "automator")
                assertNotEquals(snapshot.errors.toString(), "unavailable", snapshot.status)
                assertTrue(flatten(snapshot.roots).any { it["packageName"] == "com.android.systemui" })
                assertTrue(snapshot.roots.any { (it["window"] as? Map<*, *>)?.containsKey("layer") == true })
            } finally { device.pressBack(); device.waitForIdle() }
        }
    }

    @Test fun accessibilityTraversalHonorsNodeAndDepthLimits() {
        ActivityScenario.launch(UltronActionActivity::class.java).use {
            InteractionHelper.getUiDevice().waitForIdle()
            val limited = SnapshotCapture.capture(mode = "automator", maxNodes = 2)
            assertTrue(limited.truncated)
            assertTrue(limited.nodeCount <= 2)
            assertEquals(flatten(limited.roots).size, limited.nodeCount)
            val shallow = SnapshotCapture.capture(mode = "automator", maxDepth = 0)
            assertTrue(shallow.truncated)
            assertTrue(flatten(shallow.roots).size == shallow.roots.size)
        }
    }

    @Test fun webViewDomIsIncludedInBothModesWithPasswordRedactionAndShadowContent() = withWeb { scenario ->
        evaluate(scenario, """
            var password = document.createElement('input');
            password.id = 'password'; password.type = 'password'; password.value = 'dom-secret-932';
            password.setAttribute('aria-label', 'dom-secret-932'); document.body.appendChild(password);
            var host = document.createElement('section'); host.id = 'shadow'; document.body.appendChild(host);
            host.attachShadow({mode:'open'}).innerHTML = '<button>Shadow action</button>';
            var frame = document.createElement('iframe'); document.body.appendChild(frame);
            frame.contentDocument.body.innerHTML = '<button>Frame action</button>';
        """.trimIndent())
        for (mode in listOf("ultron", "automator")) {
            InteractionHelper.getUiDevice().waitForIdle()
            val snapshot = SnapshotCapture.capture(mode = mode)
            assertEquals("$mode: ${snapshot.errors}", "captured", snapshot.status)
            val nodes = flatten(snapshot.roots)
            assertEquals("$mode count", nodes.size, snapshot.nodeCount)
            assertTrue("$mode missing DOM", nodes.any { it["source"] == "web" && it["text"] == "Submit" })
            assertTrue("$mode missing shadow", nodes.any { it["source"] == "web" && it["text"] == "Shadow action" })
            assertTrue("$mode missing frame", nodes.any { it["source"] == "web" && it["text"] == "Frame action" })
            val password = nodes.single { (it["attributes"] as? Map<*, *>)?.get("id") == "password" }
            assertEquals("[REDACTED]", password["value"])
            assertFalse("$mode leaked password", Gson().toJson(snapshot).contains("dom-secret-932"))
            assertTrue(nodes.any { it["dom_status"] == "captured" })
        }
    }

    @Test fun domSharesNativeBudgetsAndDoesNotForceJavascriptEnabled() = withWeb { scenario ->
        evaluate(scenario, "for(var i=0;i<200;i++){document.body.appendChild(document.createElement('button'));}")
        for (mode in listOf("ultron", "automator")) {
            val snapshot = SnapshotCapture.capture(mode = mode, maxNodes = 25)
            assertTrue("$mode count ${snapshot.nodeCount}", snapshot.nodeCount <= 25)
            assertEquals(flatten(snapshot.roots).size, snapshot.nodeCount)
            assertTrue(snapshot.truncated)
            val full = SnapshotCapture.capture(mode = mode)
            val webDepth = depth(full.roots) { it["dom_status"] != null }!!
            val shallow = SnapshotCapture.capture(mode = mode, maxDepth = webDepth)
            assertTrue(shallow.truncated)
            assertFalse(flatten(shallow.roots).any { it["source"] == "web" })
        }
        scenario.onActivity { it.webView.settings.javaScriptEnabled = false }
        val unavailable = SnapshotCapture.capture()
        assertEquals("partial", unavailable.status)
        assertTrue(flatten(unavailable.roots).any { it["dom_status"] == "unavailable" })
        assertTrue(flatten(unavailable.roots).any { it["dom_error"] == "javascript_disabled" })
        scenario.onActivity { assertFalse(it.webView.settings.javaScriptEnabled) }
    }

    @Test fun repeatedSnapshotsDoNotClickOrModifyWebInputs() = withWeb { scenario ->
        evaluate(scenario, "document.getElementById('input').value='Unchanged';")
        repeat(3) { SnapshotCapture.capture() }
        val nodes = flatten(SnapshotCapture.capture().roots)
        assertEquals("Unchanged", nodes.single { (it["attributes"] as? Map<*, *>)?.get("id") == "input" }["value"])
        assertEquals("0", nodes.single { (it["attributes"] as? Map<*, *>)?.get("id") == "clicks" }["text"])
    }

    @Test fun postOperationSnapshotsAutomaticallyIncludeDomInTheSessionMode() = withWeb {
        UltronSnapshots.setEnabled(true)
        for (mode in listOf("ultron", "automator")) {
            SnapshotCapture.setMode(mode)
            val params = Gson().fromJson("""{"matcher":{"class":"LlmViewMatchers","name":"propertyEquals","args":["className","android.webkit.WebView"]},"action":"click","timeoutMs":0}""",
                UltronOperationParams::class.java)
            val result = UltronLlmOperation(false).handleInternal(params)
            assertEquals(result.operation.error, "success", result.operation.status)
            assertEquals(result.snapshot.errors.toString(), "captured", result.snapshot.status)
            assertEquals(mode, result.snapshot.mode)
            assertTrue(flatten(result.snapshot.roots).any { it["source"] == "web" && it["text"] == "Submit" })
        }
    }

    @Test fun inaccessibleFramePreservesTheNativeTreeAndReportsAPartialDom() = withWeb { scenario ->
        evaluate(scenario, """
            var isolated = document.createElement('iframe'); isolated.id = 'isolated';
            isolated.sandbox = 'allow-scripts'; isolated.srcdoc = '<button>Isolated frame</button>';
            document.body.appendChild(isolated);
        """.trimIndent())
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (evaluate(scenario, "document.getElementById('isolated').contentDocument === null") != "true" &&
            android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
        val snapshot = SnapshotCapture.capture()
        assertEquals("partial", snapshot.status)
        assertTrue(snapshot.errors.any { it.contains("frame DOM is inaccessible") })
        val nodes = flatten(snapshot.roots)
        assertTrue(nodes.any { it["source"] == "espresso" })
        assertTrue(nodes.any { it["source"] == "web" && it["text"] == "Submit" })
    }

    private fun withWeb(block: (ActivityScenario<UltronWebActivity>) -> Unit) {
        ActivityScenario.launch(UltronWebActivity::class.java).use { scenario ->
            lateinit var activity: UltronWebActivity
            scenario.onActivity { activity = it }
            assertTrue("WebView did not load", activity.pageLoaded.await(15, TimeUnit.SECONDS))
            block(scenario)
        }
    }

    private fun evaluate(scenario: ActivityScenario<UltronWebActivity>, script: String): String {
        val done = CountDownLatch(1)
        var result = ""
        scenario.onActivity { it.webView.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun flatten(roots: List<Map<String, Any?>>): List<Map<String, Any?>> = roots.flatMap {
        listOf(it) + flatten(it["children"] as? List<Map<String, Any?>> ?: emptyList())
    }

    @Suppress("UNCHECKED_CAST")
    private fun depth(roots: List<Map<String, Any?>>, current: Int = 0, predicate: (Map<String, Any?>) -> Boolean): Int? {
        for (node in roots) {
            if (predicate(node)) return current
            depth(node["children"] as? List<Map<String, Any?>> ?: emptyList(), current + 1, predicate)?.let { return it }
        }
        return null
    }
}
