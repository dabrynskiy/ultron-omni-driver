package io.appium.espressoserver.test.ultron

import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.model.Evaluation
import androidx.test.espresso.web.webdriver.DriverAtoms
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.runner.AndroidJUnit4
import com.atiurin.ultron.core.common.Operation
import com.atiurin.ultron.core.common.OperationResult
import com.atiurin.ultron.core.config.UltronConfig
import com.atiurin.ultron.core.espressoweb.UltronWebLifecycle
import com.atiurin.ultron.core.espressoweb.operation.EspressoWebOperationType
import com.atiurin.ultron.listeners.UltronLifecycleListener
import io.appium.espressoserver.lib.handlers.WebAtoms
import io.appium.espressoserver.lib.helpers.EspressoViewsCache
import io.appium.espressoserver.lib.helpers.ViewState
import io.appium.espressoserver.lib.helpers.executeWebForAppium
import io.appium.espressoserver.lib.model.EspressoElement
import io.appium.espressoserver.lib.model.web.WebAtom
import io.appium.espressoserver.lib.model.web.WebAtomsMethod
import io.appium.espressoserver.lib.model.web.WebAtomsParams
import io.appium.espressoserver.lib.viewmatcher.withView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class UltronWebTest {
    private lateinit var scenario: ActivityScenario<UltronWebActivity>
    private lateinit var webView: WebView
    private lateinit var webViewId: String
    private var originalTimeout = 0L
    private val results = mutableListOf<OperationResult<Operation>>()
    private val listener = object : UltronLifecycleListener() {
        override fun after(operationResult: OperationResult<Operation>) {
            results.add(operationResult)
        }
    }

    @Before
    fun setUp() {
        originalTimeout = UltronConfig.Espresso.ACTION_TIMEOUT
        UltronConfig.Espresso.ACTION_TIMEOUT = 3000
        UltronWebLifecycle.addListener(listener)
        scenario = ActivityScenario.launch(UltronWebActivity::class.java)
        lateinit var activity: UltronWebActivity
        scenario.onActivity {
            activity = it
            webView = it.webView
            webViewId = EspressoElement(ViewState(webView)).element
        }
        assertTrue("Local WebView page did not load", activity.pageLoaded.await(15, TimeUnit.SECONDS))
    }

    @After
    fun tearDown() {
        scenario.close()
        EspressoViewsCache.reset()
        UltronWebLifecycle.removeListener(listener.id)
        UltronConfig.Espresso.ACTION_TIMEOUT = originalTimeout
    }

    @Test
    fun webAtomsKeepEvaluationAndTextResultsAndEmitWebEvents() {
        val result = runChain(
            find("input"),
            WebAtomsMethod("perform", WebAtom("webKeys", arrayOf("Hello Ultron"))),
            find("submit"),
            WebAtomsMethod("perform", WebAtom("webClick")),
        )
        assertTrue(result is Evaluation)
        assertEquals(0, (result as Evaluation).status)
        assertNull(result.value)
        assertEquals("Hello Ultron", text("status"))
        assertEquals("1", text("clicks"))
        assertTrue(results.any { it.operation.type == EspressoWebOperationType.WEB_KEYS })
        assertEquals(1, results.count { it.operation.type == EspressoWebOperationType.WEB_CLICK })
        assertTrue(results.all { it.success })
    }

    @Test
    fun retryOfLaterSelectionDoesNotReplaySuccessfulClick() {
        assertEquals(
            "Loaded",
            runChain(
                find("delayed"),
                WebAtomsMethod("perform", WebAtom("webClick")),
                find("late"),
                WebAtomsMethod("perform", WebAtom("getText")),
            ),
        )
        assertEquals("1", text("clicks"))
        assertEquals(1, results.count { it.operation.type == EspressoWebOperationType.WEB_CLICK })
    }

    @Test
    fun forceJavascriptOptionIsExecutedThroughUltron() {
        scenario.onActivity { it.webView.settings.javaScriptEnabled = false }
        assertEquals(
            "Ready",
            WebAtoms().handleInternal(WebAtomsParams(
                webviewEl = webViewId,
                _forceJavascriptEnabled = true,
                _methodChain = listOf(find("status"), WebAtomsMethod("perform", WebAtom("getText"))),
            )),
        )
        scenario.onActivity { assertTrue(it.webView.settings.javaScriptEnabled) }
        assertEquals(EspressoWebOperationType.WEB_EVAL_JS_SCRIPT, results.first().operation.type)
    }

    @Test
    fun failedSelectionNotifiesListenerAndUnwrapsReflectionFailure() {
        UltronConfig.Espresso.ACTION_TIMEOUT = 0
        val error = runCatching { runChain(find("missing-element")) }.exceptionOrNull()
        assertNotNull(error)
        assertFalse(error is InvocationTargetException)
        assertEquals(1, results.size)
        assertFalse(results.single().success)
    }

    @Test
    fun webAdapterRetriesTransientFailureAndPreservesEvaluation() {
        var attempts = 0
        val interaction = executeWebForAppium("temporary web failure", EspressoWebOperationType.WEB_CLICK) {
            attempts++
            if (attempts == 1) throw IllegalStateException("not ready yet")
            onWebView(withView(webView))
                .withElement(DriverAtoms.findElement(Locator.ID, "submit"))
                .perform(DriverAtoms.webClick())
        }
        val evaluation = interaction.get() as Evaluation
        assertEquals(0, evaluation.status)
        assertNull(evaluation.value)
        assertEquals(2, attempts)
        assertEquals(1, results.size)
        assertTrue(results.single().success)
        assertEquals("1", text("clicks"))
    }

    private fun find(id: String) = WebAtomsMethod("withElement", WebAtom("findElement", arrayOf(Locator.ID, id)))

    private fun text(id: String) = runChain(find(id), WebAtomsMethod("perform", WebAtom("getText")))

    private fun runChain(vararg methods: WebAtomsMethod): Any? =
        WebAtoms().handleInternal(WebAtomsParams(webviewEl = webViewId, _methodChain = methods.toList()))
}
