package io.appium.espressoserver.lib.handlers

import android.view.View
import android.webkit.WebView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.web.assertion.WebViewAssertions.webMatches
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.sugar.Web.WebInteraction
import androidx.test.espresso.web.webdriver.DriverAtoms
import androidx.test.espresso.web.webdriver.Locator
import com.atiurin.ultron.core.config.UltronConfig
import com.atiurin.ultron.core.espressoweb.operation.EspressoWebOperationType
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.appium.espressoserver.lib.helpers.executeWebForAppium
import io.appium.espressoserver.lib.llm.SnapshotCapture
import io.appium.espressoserver.lib.llm.UltronSnapshotResult
import io.appium.espressoserver.lib.model.AppiumParams
import io.appium.espressoserver.lib.model.toJsonMatcher
import io.appium.espressoserver.lib.viewmatcher.withView
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo

class UltronWebParams : AppiumParams() {
    val webViewMatcher: JsonObject? = null
    val locator: JsonObject? = null
    val frames: JsonArray = JsonArray()
    val action: String? = null
    val assertion: String? = null
    val text: String? = null
    val timeoutMs: Long? = null

    companion object {
        fun validateJson(body: String) = validateOperationJson(body,
            strings = setOf("action", "assertion", "text"), integers = setOf("timeoutMs"),
            objects = setOf("webViewMatcher", "locator"), arrays = setOf("frames"))
    }
}

class UltronWebOperation(private val assertion: Boolean) : RequestHandler<UltronWebParams, UltronSnapshotResult> {
    override fun handleInternal(params: UltronWebParams): UltronSnapshotResult = prepare(params).execute()

    internal fun prepare(p: UltronWebParams): UltronPreparedOperation {
        validateOperationTimeout(p.timeoutMs)
        val scoped = (p.webViewMatcher ?: invalidOperation("webViewMatcher is required")).toString().toJsonMatcher().query
        if (scoped.scope != null) invalidOperation("webViewMatcher does not support scope")
        @Suppress("UNCHECKED_CAST")
        val matcher = allOf(isAssignableFrom(WebView::class.java), scoped.matcher as Matcher<View>)
        val locator = p.locator ?: invalidOperation("locator is required")
        if (locator.keySet() != setOf("using", "value") || locator.entrySet().any {
                !it.value.isJsonPrimitive || !it.value.asJsonPrimitive.isString }) invalidOperation("locator requires string using and value")
        val using = Locator.entries.find { it.name.equals(locator["using"].asString, ignoreCase = true) }
            ?: invalidOperation("Unsupported Web locator")
        val value = locator["value"].asString
        // Factories and frame shapes are validated now; element lookup is deferred to execution.
        DriverAtoms.findMultipleElements(using, value)
        if (p.frames.size() > 16) invalidOperation("frames must contain at most 16 entries")
        val frames = p.frames.map { frame ->
            if (!frame.isJsonObject) invalidOperation("Each frame must be an object")
            val f = frame.asJsonObject
            when (f.keySet()) {
                setOf("index") -> {
                    val index = f["index"]
                    if (!index.isJsonPrimitive || !index.asJsonPrimitive.isNumber ||
                        runCatching { index.asBigDecimal.intValueExact() }.getOrNull()?.let { it >= 0 } != true)
                        invalidOperation("Frame index must be a non-negative integer")
                    index.asInt to null
                }
                setOf("idOrName") -> {
                    val id = f["idOrName"]
                    if (!id.isJsonPrimitive || !id.asJsonPrimitive.isString || id.asString.isBlank())
                        invalidOperation("Frame idOrName must be a nonblank string")
                    null to id.asString
                }
                else -> invalidOperation("Frame requires only index or idOrName")
            }
        }
        val kind = if (assertion) p.assertion ?: "exists" else p.action
        if (assertion) {
            if (p.action != null || kind !in setOf("exists", "notExists", "textEquals", "textContains"))
                invalidOperation("Unsupported Web assertion")
            if ((kind in setOf("textEquals", "textContains")) != (p.text != null))
                invalidOperation("text is required only for Web text assertions")
        } else {
            if (p.assertion != null || kind !in setOf("click", "clearText", "typeText", "text", "scrollIntoView"))
                invalidOperation("Unsupported Web action")
            if ((kind == "typeText") != (p.text != null)) invalidOperation("text is required only for typeText")
        }
        val diagnostics = UltronDiagnosticPlan {
            SnapshotCapture.captureWeb(matcher, p.frames)
        }
        return UltronPreparedOperation("web", diagnostics, !assertion && p.text != null) {
            observedUltronOperation("web", kind!!, diagnostics, !assertion && p.text != null) {
                var result: Any? = null
                executeWebForAppium(name = kind, type = when (kind) {
                    "click" -> EspressoWebOperationType.WEB_CLICK
                    "clearText" -> EspressoWebOperationType.WEB_CLEAR_ELEMENT
                    "typeText" -> EspressoWebOperationType.WEB_KEYS
                    "text" -> EspressoWebOperationType.WEB_GET_TEXT
                    "scrollIntoView" -> EspressoWebOperationType.WEB_SCROLL_INTO_VIEW
                    else -> EspressoWebOperationType.WEB_ASSERT_THAT
                }, timeoutMs = p.timeoutMs ?: if (assertion) UltronConfig.Espresso.ASSERTION_TIMEOUT else UltronConfig.Espresso.ACTION_TIMEOUT) {
                    // A fresh interaction per attempt: no cached WebView, frame or element references.
                    lateinit var webView: WebView
                    onView(matcher).check { view, error ->
                        if (error != null) throw error
                        webView = view as WebView
                    }
                    var interaction: WebInteraction<*> = onWebView(withView(webView))
                    for ((index, id) in frames) {
                        val atom = if (index != null) DriverAtoms.selectFrameByIndex(index)
                            else DriverAtoms.selectFrameByIdOrName(id!!)
                        val window = interaction.perform(atom).get()
                        interaction = onWebView(withView(webView)).inWindow(window)
                    }
                    val matches = interaction.perform(DriverAtoms.findMultipleElements(using, value)).get()
                    if (kind == "notExists") {
                        check(matches.isEmpty()) { "Expected no Web elements, found ${matches.size}" }
                        interaction
                    } else {
                        check(matches.size == 1) { "Expected one Web element, found ${matches.size}" }
                        interaction = interaction.withElement(matches.single())
                        when (kind) {
                            "exists" -> interaction
                            "textEquals" -> interaction.check(webMatches(DriverAtoms.getText(), equalTo(p.text!!)))
                            "textContains" -> interaction.check(webMatches(DriverAtoms.getText(), containsString(p.text!!)))
                            "click" -> interaction.perform(DriverAtoms.webClick())
                            "clearText" -> interaction.perform(DriverAtoms.clearElement())
                            "typeText" -> interaction.perform(DriverAtoms.webKeys(p.text!!))
                            "scrollIntoView" -> interaction.perform(DriverAtoms.webScrollIntoView()).also { result = it.get() }
                            "text" -> interaction.perform(DriverAtoms.getText()).also { result = it.get() }
                            else -> error("Unsupported Web operation")
                        }
                    }
                }
                result
            }
        }
    }
}
