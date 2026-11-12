package io.appium.espressoserver.test.ultron

import android.os.SystemClock
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import androidx.test.uiautomator.By
import com.google.gson.Gson
import com.google.gson.JsonParser
import io.appium.espressoserver.lib.helpers.InteractionHelper
import io.appium.espressoserver.lib.http.Server
import io.appium.espressoserver.lib.model.GlobalSession
import io.appium.espressoserver.lib.model.SessionParams
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in fixture for test/locator-android-smoke.mjs: actual Android HTTP -> stdio MCP -> checker. */
@RunWith(AndroidJUnit4::class)
class SnapshotLocatorDeviceTest {
    @Test fun serveLocatorFixture() {
        val runId = InstrumentationRegistry.getArguments().getString("locatorRunId")
        assumeTrue("Run through locator-android-smoke.mjs", runId != null)
        require(runId!!.matches(Regex("[a-zA-Z0-9-]+")))
        val directory = InstrumentationRegistry.getInstrumentation().context.filesDir
        val ready = File(directory, "locator-ready-$runId.json")
        val stop = File(directory, "locator-stop-$runId")
        val sensitiveRequest = File(directory, "locator-sensitive-$runId")
        val sensitiveReady = File(directory, "locator-sensitive-ready-$runId")
        val server = Server()
        try {
            ActivityScenario.launch(UltronWebActivity::class.java).use { scenario ->
                lateinit var activity: UltronWebActivity
                scenario.onActivity { activity = it }
                assertTrue("WebView did not load", activity.pageLoaded.await(15, TimeUnit.SECONDS))
                scenario.onActivity {
                    (it.webView.parent as ViewGroup).removeView(it.webView)
                    it.setContentView(LinearLayout(it).apply {
                        orientation = LinearLayout.VERTICAL
                        for (label in listOf("Locator unique", "Locator duplicate", "Locator duplicate")) {
                            addView(Button(it).apply { text = label; isAllCaps = false; contentDescription = label })
                        }
                        addView(it.webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                    })
                }
                val references = JsonParser.parseString(evaluate(scenario, """
                    (function() {
                        document.body.insertAdjacentHTML('beforeend',
                            '<button aria-label="Duplicate">One</button><button aria-label="Duplicate">Two</button>' +
                            '<button id="shared">Main</button>');
                        var host = document.createElement('section'); host.id = 'shadow'; document.body.appendChild(host);
                        host.attachShadow({mode:'open'}).innerHTML = '<button id="shared">Shadow</button>';
                        var frame = document.createElement('iframe'); frame.id = 'frame'; document.body.appendChild(frame);
                        frame.contentDocument.body.innerHTML = '<button id="shared">Frame</button>';
                        return JSON.stringify({
                            submit: document.querySelectorAll('#submit').length,
                            duplicate: document.querySelectorAll('button[aria-label="Duplicate"]').length,
                            missing: document.querySelectorAll('#locator-missing').length,
                            shared: document.querySelectorAll('#shared').length,
                            shadow: host.shadowRoot.querySelectorAll('#shared').length,
                            frame: frame.contentDocument.querySelectorAll('#shared').length
                        });
                    })()
                """.trimIndent())).asString
                val device = InteractionHelper.getUiDevice()
                device.waitForIdle()
                val nativeCounts = listOf("Locator unique", "Locator duplicate", "Locator missing").associateWith {
                    device.findObjects(By.text(it)).size
                }
                assertEquals(1, nativeCounts["Locator unique"])
                assertEquals(2, nativeCounts["Locator duplicate"])
                GlobalSession.create(SessionParams.W3CCapabilities(emptyList(), emptyMap()))
                server.setServerSocketFactory {
                    object : ServerSocket() {
                        override fun bind(endpoint: SocketAddress?) = super.bind(InetSocketAddress("127.0.0.1", 0))
                    }
                }
                server.start()
                ready.writeText(Gson().toJson(mapOf("port" to server.listeningPort,
                    "sessionId" to GlobalSession.sessionId, "nativeCounts" to nativeCounts,
                    "domCounts" to JsonParser.parseString(references))))
                val deadline = SystemClock.elapsedRealtime() + 180000
                while (!stop.exists() && SystemClock.elapsedRealtime() < deadline) {
                    if (sensitiveRequest.exists() && !sensitiveReady.exists()) {
                        evaluate(scenario, "document.body.insertAdjacentHTML('beforeend', '<input id=\"password\" type=\"password\" value=\"locator-secret-932\">');")
                        scenario.onActivity {
                            (it.webView.parent as LinearLayout).addView(EditText(it).apply {
                                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                                isFocusable = false
                                setText("locator-secret-932")
                            }, 0)
                        }
                        device.waitForIdle()
                        sensitiveReady.writeText("ready")
                    }
                    Thread.sleep(100)
                }
                assertTrue("Host did not finish within 180 seconds", stop.exists())
                assertEquals("0", JsonParser.parseString(evaluate(scenario,
                    "document.getElementById('clicks').textContent")).asString)
                assertEquals("", JsonParser.parseString(evaluate(scenario,
                    "document.getElementById('input').value")).asString)
            }
        } finally {
            server.stop()
            GlobalSession.delete()
            ready.delete()
            stop.delete()
            sensitiveRequest.delete()
            sensitiveReady.delete()
        }
    }

    private fun evaluate(scenario: ActivityScenario<UltronWebActivity>, script: String): String {
        val done = CountDownLatch(1)
        var result = ""
        scenario.onActivity { it.webView.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue("Javascript evaluation timed out", done.await(5, TimeUnit.SECONDS))
        return result
    }
}
