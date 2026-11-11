package io.appium.espressoserver.test.ultron

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.runner.AndroidJUnit4
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.appium.espressoserver.lib.http.Server
import io.appium.espressoserver.lib.llm.UltronSnapshots
import io.appium.espressoserver.lib.model.GlobalSession
import io.appium.espressoserver.lib.model.SessionParams
import com.atiurin.ultron.core.config.UltronCommonConfig
import com.atiurin.ultron.core.config.UltronConfig
import io.appium.espressoserver.lib.model.settings.UltronSnapshotSetting
import io.appium.espressoserver.lib.handlers.exceptions.InvalidArgumentException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.InetSocketAddress

/** Tests the real NanoHTTPD routes and Gson response, not only direct Kotlin calls. */
@RunWith(AndroidJUnit4::class)
class ViewSnapshotHttpTest {
    private var port = 0

    private fun startServer(server: Server) {
        // A standalone instrumentation server may already own 6791 on this emulator.
        server.setServerSocketFactory {
            object : ServerSocket() {
                override fun bind(endpoint: SocketAddress?) = super.bind(InetSocketAddress("127.0.0.1", 0))
            }
        }
        server.start()
        port = server.listeningPort
    }

    @Test fun positionalAndDoubleClicksUseUltronAndReturnSnapshots() = withSession { session, scenario ->
        val positions = listOf(
            Triple("clickTopLeft", 12, 12), Triple("clickTopCenter", null, 12),
            Triple("clickTopRight", -12, 12), Triple("clickCenterRight", -12, null),
            Triple("clickBottomRight", -12, -12), Triple("clickBottomCenter", null, -12),
            Triple("clickBottomLeft", 12, -12), Triple("clickCenterLeft", 12, null),
        )
        for ((index, position) in positions.withIndex()) {
            val (action, offsetX, offsetY) = position
            val options = buildString {
                offsetX?.let { append(",\"offsetX\":$it") }
                offsetY?.let { append(",\"offsetY\":$it") }
            }
            val response = request(session, "ultron_act",
                """{"matcher":${viewMatcher(UltronActionActivity.BUTTON_ID)},"action":"$action","name":"Continue","timeoutMs":0$options}""")
                .getAsJsonObject("value")
            assertEquals(response.toString(), "success", response.getAsJsonObject("operation")["status"].asString)
            assertTrue(response.getAsJsonObject("operation")["name"].asString.contains("Continue"))
            assertEquals("captured", response.getAsJsonObject("snapshot")["status"].asString)
            scenario.onActivity { activity ->
                assertEquals(index + 1, activity.clicks)
                val x = when { offsetX == null -> activity.button.width / 2f
                    offsetX >= 0 -> offsetX.toFloat()
                    else -> activity.button.width - 1f + offsetX }
                val y = when { offsetY == null -> activity.button.height / 2f
                    offsetY >= 0 -> offsetY.toFloat()
                    else -> activity.button.height - 1f + offsetY }
                assertEquals("$action x", x, activity.lastTouchX, 2f)
                assertEquals("$action y", y, activity.lastTouchY, 2f)
            }
        }
        val doubled = request(session, "ultron_act",
            """{"matcher":${viewMatcher(UltronActionActivity.BUTTON_ID)},"action":"doubleClick","autoScroll":false,"timeoutMs":0}""")
            .getAsJsonObject("value")
        assertEquals("success", doubled.getAsJsonObject("operation")["status"].asString)
        assertEquals("DOUBLE_CLICK", doubled.getAsJsonObject("operation")["type"].asString)
        scenario.onActivity { assertEquals(10, it.clicks) }
    }

    @Test fun keyOptionsReachTheFocusedViewAndKeyboardCloseReturnsAnOperation() = withSession { session, scenario ->
        scenario.onActivity { it.input.requestFocus() }
        fun press(options: String) = request(session, "ultron_act",
            """{"matcher":${viewMatcher(UltronActionActivity.INPUT_ID)},"action":"pressKey","timeoutMs":0,$options}""")
            .getAsJsonObject("value")
        val shifted = press(""""keyCode":${KeyEvent.KEYCODE_A},"metaState":${KeyEvent.META_SHIFT_ON},"flags":${KeyEvent.FLAG_SOFT_KEYBOARD}""")
        assertEquals(shifted.toString(), "success", shifted.getAsJsonObject("operation")["status"].asString)
        assertEquals("PRESS_KEY", shifted.getAsJsonObject("operation")["type"].asString)
        assertEquals("captured", shifted.getAsJsonObject("snapshot")["status"].asString)
        scenario.onActivity { activity ->
            assertEquals("A", activity.input.text.toString())
            val events = activity.keyEvents.filter { it.keyCode == KeyEvent.KEYCODE_A }
            assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), events.map { it.action })
            assertTrue(events.all { it.metaState and KeyEvent.META_SHIFT_ON != 0 })
            assertTrue(events.all { it.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0 })
        }
        assertEquals("success", press(""""keyCode":${KeyEvent.KEYCODE_B}""").getAsJsonObject("operation")["status"].asString)
        scenario.onActivity { assertEquals("Ab", it.input.text.toString()) }
        val closed = request(session, "ultron_act",
            """{"matcher":${viewMatcher(UltronActionActivity.INPUT_ID)},"action":"closeSoftKeyboard","timeoutMs":0}""")
            .getAsJsonObject("value")
        assertEquals("success", closed.getAsJsonObject("operation")["status"].asString)
        assertEquals("CLOSE_SOFT_KEYBOARD", closed.getAsJsonObject("operation")["type"].asString)
        assertEquals("captured", closed.getAsJsonObject("snapshot")["status"].asString)
    }

    @Test fun softKeyboardKeyPairsKeepDispatchOrderWhileImeStartsAndStops() = withSession { session, scenario ->
        repeat(20) { iteration ->
            scenario.onActivity { activity ->
                activity.input.setText("")
                activity.input.requestFocus()
                activity.keyEvents.clear()
                val ime = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                if (iteration % 2 == 0) ime.showSoftInput(activity.input, InputMethodManager.SHOW_IMPLICIT)
                else ime.hideSoftInputFromWindow(activity.input.windowToken, 0)
            }
            // The displayed matcher is deliberately not the focused input.
            val value = request(session, "ultron_act",
                """{"matcher":${viewMatcher(UltronActionActivity.BUTTON_ID)},"action":"pressKey","keyCode":${KeyEvent.KEYCODE_A},"metaState":${KeyEvent.META_SHIFT_ON},"flags":${KeyEvent.FLAG_SOFT_KEYBOARD},"timeoutMs":0}""")
                .getAsJsonObject("value")
            assertEquals("iteration $iteration: $value", "success", value.getAsJsonObject("operation")["status"].asString)
            scenario.onActivity { activity ->
                val events = activity.keyEvents.filter { it.keyCode == KeyEvent.KEYCODE_A }
                assertEquals("iteration $iteration: $events", listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), events.map { it.action })
                assertEquals("iteration $iteration", "A", activity.input.text.toString())
                assertTrue(events.all { it.metaState and KeyEvent.META_SHIFT_ON != 0 })
                assertTrue(events.all { it.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0 })
            }
        }
    }

    @Test fun autoScrollIsAppliedAfterTimeoutAndDoesNotChangeTheGlobalDefault() = withSession { session, scenario ->
        val previous = UltronConfig.Espresso.ViewActionConfig.autoScroll
        UltronConfig.Espresso.ViewActionConfig.autoScroll = true
        try {
            lateinit var scroll: ScrollView
            scenario.onActivity { activity ->
                (activity.button.parent as ViewGroup).removeView(activity.button)
                scroll = ScrollView(activity).apply {
                    addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(View(activity), LinearLayout.LayoutParams(1, 4000))
                        addView(activity.button)
                    })
                }
                activity.setContentView(scroll)
            }
            fun click(options: String) = request(session, "ultron_act",
                """{"matcher":${viewMatcher(UltronActionActivity.BUTTON_ID)},"action":"click","timeoutMs":0$options}""")
                .getAsJsonObject("value")
            val failed = click(",\"autoScroll\":false")
            assertEquals("failure", failed.getAsJsonObject("operation")["status"].asString)
            assertEquals("captured", failed.getAsJsonObject("snapshot")["status"].asString)
            scenario.onActivity { assertEquals(0, it.clicks) }
            assertEquals("success", click(",\"autoScroll\":true").getAsJsonObject("operation")["status"].asString)
            scenario.onActivity { activity ->
                assertEquals(1, activity.clicks)
                scroll.scrollTo(0, 0)
            }
            assertEquals("success", click("").getAsJsonObject("operation")["status"].asString)
            assertTrue(UltronConfig.Espresso.ViewActionConfig.autoScroll)
            scenario.onActivity { assertEquals(2, it.clicks) }
        } finally { UltronConfig.Espresso.ViewActionConfig.autoScroll = previous }
    }

    @Test fun invalidActionOptionsFailBeforeTouchingTheView() = withSession { session, scenario ->
        for (options in listOf(
            """"action":"pressKey"""", """"action":"pressKey","keyCode":0""",
            """"action":"pressKey","keyCode":${KeyEvent.getMaxKeyCode()}""",
            """"action":"pressKey","keyCode":29,"flags":-1""",
            """"action":"click","keyCode":29""", """"action":"click","metaState":0""",
            """"action":"click","flags":0""", """"action":"click","offsetX":1""",
            """"action":"clickTopCenter","offsetX":0""", """"action":"clickCenterRight","offsetY":0""",
            """"action":"click","name":""""",
        )) {
            val error = rawRequest("/session/$session/appium/execute_mobile/ultron_act",
                """{"matcher":${viewMatcher(UltronActionActivity.BUTTON_ID)},$options}""", 400)
            assertEquals(error.toString(), "invalid argument", error.getAsJsonObject("value")["error"].asString)
        }
        scenario.onActivity { assertEquals(0, it.clicks) }
        assertNull(UltronSnapshots.listener.latest)
    }

    private fun viewMatcher(id: Int) = """{"class":"LlmViewMatchers","name":"propertyEquals","args":["viewId",$id]}"""

    @Test fun recyclerHttpRouteSelectsOffscreenRowsAndRejectsInvalidArguments() = withSession { session, scenario ->
        scenario.onActivity { activity ->
            val recycler = CustomFeedView(activity).apply {
                id = UltronRecyclerActivity.LIST_ID
                layoutManager = androidx.recyclerview.widget.LinearLayoutManager(activity)
                adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
                    override fun getItemCount() = 40
                    override fun onCreateViewHolder(parent: ViewGroup, type: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder =
                        object : androidx.recyclerview.widget.RecyclerView.ViewHolder(android.widget.Button(parent.context).apply {
                            layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, 150)
                        }) {}
                    override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                        (holder.itemView as android.widget.Button).apply {
                            text = "Row $position"
                            setOnClickListener { activity.clicks++ }
                        }
                    }
                }
            }
            activity.setContentView(recycler)
        }
        val matcher = viewMatcher(UltronRecyclerActivity.LIST_ID)
        val response = request(session, "ultron_recycler",
            """{"matcher":$matcher,"action":"click","itemMatcher":{"class":"ViewMatchers","name":"withText","args":["Row 30"]},"timeoutMs":0}""")
            .getAsJsonObject("value")
        assertEquals(response.toString(), "success", response.getAsJsonObject("operation")["status"].asString)
        assertEquals("captured", response.getAsJsonObject("snapshot")["status"].asString)
        scenario.onActivity { assertEquals(1, it.clicks) }
        val invalid = rawRequest("/session/$session/appium/execute_mobile/ultron_recycler",
            """{"matcher":$matcher,"action":"click","position":-1}""", 400)
        assertEquals("invalid argument", invalid.getAsJsonObject("value")["error"].asString)
    }

    private fun withSession(block: (String, ActivityScenario<UltronActionActivity>) -> Unit) {
        val scenario = ActivityScenario.launch(UltronActionActivity::class.java)
        val server = Server()
        try {
            GlobalSession.create(SessionParams.W3CCapabilities(emptyList(), emptyMap()))
            startServer(server)
            UltronSnapshots.setEnabled(true)
            block(GlobalSession.sessionId!!, scenario)
        } finally {
            server.stop()
            GlobalSession.delete()
            scenario.close()
        }
    }

    @Test fun automaticSnapshotsAreOptInAndResetBetweenSessions() {
        val scenario = ActivityScenario.launch(UltronActionActivity::class.java)
        val server = Server()
        try {
            GlobalSession.create(SessionParams.W3CCapabilities(emptyList(), emptyMap()))
            val session = GlobalSession.sessionId!!
            startServer(server)
            fun registered() = UltronCommonConfig.getListeners().any { it.id == UltronSnapshots.listener.id }
            fun enabled() = rawRequest("/session/$session/appium/settings", null)
                .getAsJsonObject("value")["ultronSnapshotEnabled"].asBoolean
            fun enable(value: Boolean) = rawRequest("/session/$session/appium/settings",
                """{"settings":{"ultronSnapshotEnabled":$value}}""")
            val matcher = """{"class":"LlmViewMatchers","name":"propertyEquals","args":["viewId",${UltronActionActivity.BUTTON_ID}]}"""
            fun act() = request(session, "ultron_act", """{"matcher":$matcher,"action":"click","timeoutMs":0}""")
                .getAsJsonObject("value")

            assertFalse(enabled())
            assertFalse(registered())
            assertEquals("disabled", act().getAsJsonObject("snapshot")["status"].asString)
            assertFalse(registered()) // ultronAct must not silently re-register it.
            assertTrue(request(session, "ultron_last_result", null)["value"].isJsonNull)
            assertEquals("captured", request(session, "ultron_snapshot", "{}").getAsJsonObject("value")["status"].asString)
            assertFalse(registered())

            val found = rawRequest("/session/$session/element", """{"using":"-android viewmatcher","value":${com.google.gson.Gson().toJson(matcher)}}""")
                .getAsJsonObject("value")
            val id = found.entrySet().first { it.key == "ELEMENT" || it.key.startsWith("element-") }.value.asString
            fun click() = rawRequest("/session/$session/element/$id/click", "{}")
            click()
            assertNull(UltronSnapshots.listener.latest)
            enable(true)
            enable(true)
            assertTrue(enabled())
            assertEquals(1, UltronCommonConfig.getListeners().count { it.id == UltronSnapshots.listener.id })
            click()
            assertEquals("captured", UltronSnapshots.listener.latest!!.snapshot.status)
            assertEquals("captured", act().getAsJsonObject("snapshot")["status"].asString)
            enable(false)
            assertFalse(enabled())
            assertFalse(registered())
            assertNull(UltronSnapshots.listener.latest)
            click()
            assertNull(UltronSnapshots.listener.latest)
            val assertion = request(session, "ultron_assert", """{"matcher":$matcher,"timeoutMs":0}""").getAsJsonObject("value")
            assertEquals("success", assertion.getAsJsonObject("operation")["status"].asString)
            assertEquals("disabled", assertion.getAsJsonObject("snapshot")["status"].asString)
            assertFalse(registered())

            assertThrows(InvalidArgumentException::class.java) { UltronSnapshotSetting().apply("true") }
            assertFalse(enabled())
            enable(true)
            GlobalSession.delete()
            assertFalse(UltronSnapshots.enabled)
            assertFalse(registered())
            GlobalSession.create(SessionParams.W3CCapabilities(emptyList(), emptyMap()))
            assertFalse(UltronSnapshots.enabled)
            UltronSnapshots.setEnabled(true)
            GlobalSession.create(SessionParams.W3CCapabilities(emptyList(), emptyMap()))
            assertFalse(UltronSnapshots.enabled)
            assertFalse(registered())
        } finally {
            server.stop()
            GlobalSession.delete()
            scenario.close()
        }
    }

    @Test fun snapshotActionAndNativeW3cResponsesTravelThroughHttp() {
        val scenario = ActivityScenario.launch(UltronActionActivity::class.java)
        val server = Server()
        try {
            GlobalSession.create(SessionParams.W3CCapabilities(emptyList(), emptyMap()))
            val session = GlobalSession.sessionId!!
            startServer(server)
            rawRequest("/session/$session/appium/settings", """{"settings":{"ultronSnapshotEnabled":true}}""")
            val snapshot = request(session, "ultron_snapshot", "{}").getAsJsonObject("value")
            assertEquals("captured", snapshot["status"].asString)
            assertTrue(snapshot["roots"].isJsonArray)
            val matcher = """{"class":"LlmViewMatchers","name":"propertyEquals","args":["viewId",${UltronActionActivity.BUTTON_ID}]}"""
            val response = request(session, "ultron_act", """{"matcher":$matcher,"action":"click","timeoutMs":0}""").getAsJsonObject("value")
            assertEquals("success", response.getAsJsonObject("operation")["status"].asString)
            assertTrue(response.getAsJsonObject("snapshot")["roots"].isJsonArray)
            scenario.onActivity { assertEquals(1, it.clicks) }
            val last = request(session, "ultron_last_result", null).getAsJsonObject("value")
            assertEquals(response.getAsJsonObject("snapshot")["snapshotId"], last.getAsJsonObject("snapshot")["snapshotId"])
            val found = rawRequest("/session/$session/element", """{"using":"-android viewmatcher","value":${com.google.gson.Gson().toJson(matcher)}}""")
                .getAsJsonObject("value")
            val id = found.entrySet().first { it.key == "ELEMENT" || it.key.startsWith("element-") }.value.asString
            val clicked = rawRequest("/session/$session/element/$id/click", "{}")
            assertTrue(clicked["value"].isJsonNull ||
                (clicked["value"].isJsonObject && clicked.getAsJsonObject("value").size() == 0))
            scenario.onActivity { assertEquals(2, it.clicks) }
            assertNotNull(UltronSnapshots.listener.latest)
        } finally {
            server.stop()
            GlobalSession.delete()
            scenario.close()
        }
    }

    private fun request(session: String, command: String, body: String?) =
        rawRequest("/session/$session/appium/execute_mobile/$command", body)

    private fun rawRequest(path: String, body: String?, expectedStatus: Int = 200): JsonObject {
        val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        return Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10000
            val headers = "${if (body == null) "GET" else "POST"} $path HTTP/1.1\r\n" +
                "Host: localhost\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(headers.toByteArray(Charsets.UTF_8)); write(bytes); flush() }
            val response = socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
            assertTrue(response.take(1000), response.startsWith("HTTP/1.1 $expectedStatus"))
            JsonParser.parseString(response.substringAfter("\r\n\r\n")).asJsonObject
        }
    }
}
