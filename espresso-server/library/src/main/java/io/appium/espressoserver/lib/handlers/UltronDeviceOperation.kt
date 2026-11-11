package io.appium.espressoserver.lib.handlers

import android.os.SystemClock
import com.google.gson.JsonParser
import io.appium.espressoserver.lib.BuildConfig
import io.appium.espressoserver.lib.handlers.exceptions.ComposeNotSupportedException
import io.appium.espressoserver.lib.handlers.exceptions.InvalidArgumentException
import io.appium.espressoserver.lib.llm.UltronOperationInfo
import io.appium.espressoserver.lib.llm.UltronSnapshotResult
import io.appium.espressoserver.lib.llm.UltronSnapshots
import io.appium.espressoserver.lib.model.*

class UltronDeviceParams : AppiumParams() {
    val backend: String? = null
    val using: String? = null
    val value: String? = null
    val action: String? = null
    val text: String? = null
    val requireUnique: Boolean? = null

    companion object {
        fun validateJson(body: String) {
            val root = JsonParser.parseString(body)
            if (!root.isJsonObject) throw InvalidArgumentException("Operation parameters must be an object")
            for ((key, value) in root.asJsonObject.entrySet()) {
                if (key == "requireUnique") {
                    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) throw InvalidArgumentException("requireUnique must be boolean")
                    continue
                }
                if (key !in setOf("backend", "using", "value", "action", "text") ||
                    !value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
                    throw InvalidArgumentException("Invalid device operation parameter: $key")
                }
            }
        }
    }
}

/** Resolve locators at execution time and use the existing explicit backend handlers. */
class UltronDeviceOperation(private val command: String) : RequestHandler<UltronDeviceParams, UltronSnapshotResult> {
    override fun handleInternal(params: UltronDeviceParams): UltronSnapshotResult = prepare(params).invoke()

    internal fun prepare(p: UltronDeviceParams): () -> UltronSnapshotResult {
        fun invalid(message: String): Nothing = throw InvalidArgumentException(message)
        val element = command == "ultronElement"
        var locator: Locator? = null
        if (element) {
            if (p.backend !in setOf("espresso", "compose")) invalid("backend must be espresso or compose")
            if (p.backend == "compose" && !BuildConfig.COMPOSE_SUPPORT) throw ComposeNotSupportedException()
            val strategy = Strategy.entries.find { it.strategyName == p.using }
                ?: if (p.using == "tag name") Strategy.VIEW_TAG else invalid("Unsupported locator strategy")
            val supported = if (p.backend == "compose") setOf(Strategy.VIEW_TAG, Strategy.TEXT, Strategy.LINK_TEXT, Strategy.ACCESSIBILITY_ID, Strategy.XPATH)
                else setOf(Strategy.ID, Strategy.CLASS_NAME, Strategy.TEXT, Strategy.ACCESSIBILITY_ID, Strategy.XPATH, Strategy.VIEW_TAG, Strategy.VIEWMATCHER, Strategy.DATAMATCHER)
            if (strategy !in supported) invalid("Locator strategy is not supported by ${p.backend}")
            val value = p.value ?: invalid("value is required")
            if (strategy == Strategy.XPATH) compileXpathExpression(value)
            if (strategy in setOf(Strategy.VIEWMATCHER, Strategy.DATAMATCHER)) value.toJsonMatcher()
            if (p.action !in setOf("find", "click", "clear", "typeText", "replaceText", "text", "displayed", "enabled", "selected")) invalid("Unsupported element action")
            if (p.action in setOf("typeText", "replaceText") && p.text == null) invalid("text is required")
            if (p.action !in setOf("typeText", "replaceText") && p.text != null) invalid("text is only valid for text input")
            locator = Locator(strategy, value)
        } else {
            if (command !in setOf("ultronBack", "ultronHideKeyboard")) invalid("Unsupported device operation")
            if (listOf(p.backend, p.using, p.value, p.action, p.text, p.requireUnique).any { it != null }) invalid("Navigation commands accept no parameters")
        }
        return { executePrepared(p, locator) }
    }

    private fun executePrepared(p: UltronDeviceParams, locator: Locator?): UltronSnapshotResult {
        val listener = UltronSnapshots.listener
        listener.beginCommand()
        val start = SystemClock.elapsedRealtime()
        var value: Any? = null
        var element: BaseElement? = null
        fun result(status: String, error: Throwable? = null): UltronSnapshotResult {
            val observed = listener.commandResult()
            return UltronSnapshotResult(
                UltronOperationInfo(status, "$command ${p.action ?: ""}".trim(), p.action ?: command,
                    SystemClock.elapsedRealtime() - start, error?.toString(), p.backend ?: "espresso"),
                observed?.snapshot ?: UltronSnapshots.captureForOperation(), value, element,
            ).redactSensitiveDetails(p.text != null)
        }
        return try {
            if (locator == null) {
                if (command == "ultronBack") Back().handleInternal(p) else HideKeyboard().handleInternal(p)
            } else {
                fun <T : AppiumParams, R> call(handler: RequestHandler<T, R>, params: T): R =
                    if (p.backend == "compose") handler.handleCompose(params) else handler.handleEspresso(params)
                element = if (p.requireUnique == true) {
                    val matches = call(FindElements(), locator)
                    check(matches.size == 1) { "Expected one element for the locator, found ${matches.size}" }
                    matches.single()
                } else call(FindElement(), locator)
                val target = AppiumParams().apply { elementId = element!!.element }
                value = when (p.action) {
                    "find" -> null
                    "click" -> { call(Click(), target); null }
                    "clear" -> { call(Clear(), target); null }
                    "typeText", "replaceText" -> {
                        call(ElementValue(p.action == "replaceText"), TextValueParams(text = p.text).apply { elementId = target.elementId }); null
                    }
                    "text" -> call(Text(), target)
                    "displayed" -> call(GetDisplayed(), target)
                    "enabled" -> call(GetEnabled(), target)
                    "selected" -> call(GetSelected(), target)
                    else -> error("Unsupported element action")
                }
            }
            result("success").also(listener::record)
        } catch (error: Throwable) {
            if (error !is Exception && error !is AssertionError) throw error
            result("failure", error).also(listener::record)
        } finally { listener.endCommand() }
    }
}
