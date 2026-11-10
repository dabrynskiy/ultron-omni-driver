package io.appium.espressoserver.test.ultron

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.google.gson.Gson
import io.appium.espressoserver.lib.handlers.UltronSnapshot
import io.appium.espressoserver.lib.handlers.UltronSnapshotParams
import io.appium.espressoserver.lib.llm.ViewHierarchyDumper
import io.appium.espressoserver.lib.llm.UltronSnapshots
import io.appium.espressoserver.lib.http.Server
import io.appium.espressoserver.lib.http.DEFAULT_PORT
import io.appium.espressoserver.lib.model.GlobalSession
import io.appium.espressoserver.lib.model.SessionParams
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.Socket

class ComposeSnapshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeSnapshotActivity>()

    @Test fun mixedTreePreservesViewsAndSerializesSemanticsWithoutReflectingComposeObjects() {
        compose.waitForIdle()
        val expected = compose.onNodeWithTag("compose-leaf", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val snapshot = ViewHierarchyDumper().capture()
        assertEquals(snapshot.errors.toString(), "captured", snapshot.status)
        val nodes = flatten(snapshot.roots)
        assertEquals(nodes.size, snapshot.nodeCount)
        assertTrue(nodes.any { it["text"] == "Native sibling" })
        assertTrue(nodes.any { it["text"] == "Embedded AndroidView" })
        val leaf = nodes.single { it["test_tag"] == "compose-leaf" }
        assertEquals("SemanticsNode", leaf["type"])
        assertTrue(leaf["node_id"] is Int)
        val config = leaf["config"] as Map<*, *>
        assertEquals(listOf("Compose text"), config["Text"])
        assertEquals(listOf("Compose description"), config["ContentDescription"])
        assertEquals(listOf("OnClick"), config["actions"])
        assertEquals(listOf("Disabled"), config["units"])
        assertEquals(listOf(mapOf("label" to "Custom action")), config["CustomActions"])
        val bounds = leaf["bounds"] as Map<*, *>
        compose.runOnUiThread {
            val location = IntArray(2)
            owner(compose.activity.host).getLocationOnScreen(location)
            assertEquals(expected.left + location[0], bounds["l"])
            assertEquals(expected.top + location[1], bounds["t"])
        }
        val json = Gson().toJsonTree(snapshot)
        assertTrue(json.toString().contains("\"type\":\"SemanticsNode\""))
    }

    @Test fun mergedSelectionAndHiddenComposeHostsAreDescribed() {
        compose.waitForIdle()
        val mergedParams = Gson().fromJson("""{"useUnmergedTree":false}""", UltronSnapshotParams::class.java)
        val merged = UltronSnapshot().handleInternal(mergedParams)
        assertEquals(merged.errors.toString(), "captured", merged.status)
        assertFalse(flatten(merged.roots).any { it["test_tag"] == "compose-leaf" })
        assertTrue(flatten(merged.roots).filter { it["semantics_root"] == true }.all { it["semantics_tree"] == "merged" })
        compose.runOnUiThread { compose.activity.hiddenHost.visibility = View.GONE }
        val hidden = ViewHierarchyDumper().capture()
        assertEquals(hidden.errors.toString(), "captured", hidden.status)
        val leaf = flatten(hidden.roots).single { it["test_tag"] == "hidden-leaf" }
        assertEquals("GONE", leaf["effective_visibility"])
    }

    @Test fun limitsApplyToTheWholeMixedTree() {
        compose.waitForIdle()
        val full = ViewHierarchyDumper().capture()
        val nodes = flatten(full.roots)
        val leafIndex = nodes.indexOfFirst { it["test_tag"] == "compose-leaf" }
        assertTrue(leafIndex > 0)
        val limited = ViewHierarchyDumper(maxNodes = leafIndex + 1).capture()
        assertEquals("partial", limited.status)
        assertTrue(limited.truncated)
        assertEquals(leafIndex + 1, limited.nodeCount)
        assertEquals(limited.nodeCount, flatten(limited.roots).size)
        assertTrue(flatten(limited.roots).any { it["test_tag"] == "compose-leaf" })
        val rootDepth = depth(full.roots, "semantics_root", true)!!
        val shallow = ViewHierarchyDumper(maxDepth = rootDepth).capture()
        assertTrue(shallow.truncated)
        assertTrue(flatten(shallow.roots).filter { it["semantics_root"] == true }
            .any { it["truncation_reason"] == "maxDepth" })
        assertFalse(flatten(shallow.roots).any { it["test_tag"] == "compose-leaf" })
    }

    @Test fun explicitAndAutomaticHttpSnapshotsContainComposeNodes() {
        compose.waitForIdle()
        val server = Server()
        try {
            GlobalSession.create(SessionParams.W3CCapabilities(emptyList(), emptyMap()))
            val session = GlobalSession.sessionId!!
            server.start()
            fun post(path: String, body: String): com.google.gson.JsonObject {
                return Socket("127.0.0.1", DEFAULT_PORT).use { socket ->
                    socket.soTimeout = 10000
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val headers = "POST /session/$session$path HTTP/1.1\r\nHost: localhost\r\n" +
                        "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply { write(headers.toByteArray(Charsets.UTF_8)); write(bytes); flush() }
                    val response = socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
                    assertTrue(response.take(200), response.startsWith("HTTP/1.1 200"))
                    Gson().fromJson(response.substringAfter("\r\n\r\n"), com.google.gson.JsonObject::class.java)
                }
            }
            val explicit = post("/appium/execute_mobile/ultron_snapshot", "{}")
            assertTrue(explicit.toString().contains("\"test_tag\":\"compose-leaf\""))
            post("/appium/settings", """{"settings":{"ultronSnapshotEnabled":true}}""")
            val automatic = post("/appium/execute_mobile/ultron_assert", """{"matcher":{"class":"ViewMatchers","name":"withText","args":["Native sibling"]},"timeoutMs":0}""")
                .getAsJsonObject("value")
            assertEquals("success", automatic.getAsJsonObject("operation")["status"].asString)
            assertTrue(automatic.getAsJsonObject("snapshot").toString().contains("\"test_tag\":\"compose-leaf\""))
        } finally {
            server.stop()
            GlobalSession.delete()
            UltronSnapshots.reset()
        }
    }

    private fun owner(view: View): View {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            try { return owner(view.getChildAt(index)) } catch (_: NoSuchElementException) { }
        }
        throw NoSuchElementException("No Compose owner")
    }

    @Suppress("UNCHECKED_CAST")
    private fun flatten(nodes: List<Map<String, Any?>>): List<Map<String, Any?>> = nodes.flatMap {
        listOf(it) + flatten(it["children"] as? List<Map<String, Any?>> ?: emptyList())
    }

    @Suppress("UNCHECKED_CAST")
    private fun depth(nodes: List<Map<String, Any?>>, key: String, value: Any?, level: Int = 0): Int? {
        for (node in nodes) {
            if (node[key] == value) return level
            depth(node["children"] as? List<Map<String, Any?>> ?: emptyList(), key, value, level + 1)?.let { return it }
        }
        return null
    }
}
