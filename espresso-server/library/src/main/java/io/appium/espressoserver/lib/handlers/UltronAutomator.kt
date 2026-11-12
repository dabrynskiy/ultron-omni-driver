package io.appium.espressoserver.lib.handlers

import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiObjectNotFoundException
import com.atiurin.ultron.core.common.CommonOperationType
import com.atiurin.ultron.core.uiautomator.uiobject2.UltronUiObject2
import io.appium.espressoserver.lib.handlers.exceptions.InvalidArgumentException
import io.appium.espressoserver.lib.helpers.InteractionHelper.getUiDevice
import io.appium.espressoserver.lib.helpers.handleUltronResult
import io.appium.espressoserver.lib.llm.UltronSnapshotResult
import io.appium.espressoserver.lib.model.AppiumParams

/** Deliberately separate from legacy uiautomator's bulk actions and boolean getters. */
class UltronAutomatorParams : AppiumParams() {
    val strategy: String? = null
    val locator: String? = null
    val packageName: String? = null
    val index: Int? = null
    val action: String? = null
    val assertion: String? = null
    val text: String? = null
    val expected: Boolean? = null
    val timeoutMs: Long? = null

    companion object {
        fun validateJson(body: String) = validateOperationJson(body,
            strings = setOf("strategy", "locator", "packageName", "action", "assertion", "text"),
            booleans = setOf("expected"), integers = setOf("index", "timeoutMs"))
    }
}

class UltronAutomatorOperation(private val assertion: Boolean) : RequestHandler<UltronAutomatorParams, UltronSnapshotResult> {
    override fun handleInternal(params: UltronAutomatorParams): UltronSnapshotResult = prepare(params).execute()

    internal fun prepare(p: UltronAutomatorParams): UltronPreparedOperation {
        val locator = p.locator ?: invalidOperation("locator is required")
        val selector = selector(p.strategy, locator).also { p.packageName?.let(it::pkg) }
        if (p.index != null && p.index < 0) invalidOperation("index must be non-negative")
        validateOperationTimeout(p.timeoutMs)
        val kind = if (assertion) p.assertion ?: "exists" else p.action
        if (assertion) {
            if (p.action != null || kind !in ASSERTIONS) invalidOperation("Unsupported Automator assertion")
            if ((kind in TEXT_ASSERTIONS) != (p.text != null)) invalidOperation("text is required only for text assertions")
            if (p.expected != null && kind !in BOOLEAN_ASSERTIONS) invalidOperation("expected is only valid for boolean assertions")
        } else {
            if (p.assertion != null || p.expected != null || kind !in ACTIONS) invalidOperation("Unsupported Automator action")
            if ((kind == "replaceText") != (p.text != null)) invalidOperation("text is required only for replaceText")
        }
        val diagnostics = UltronDiagnosticPlan.native("automator")
        return UltronPreparedOperation("automator", diagnostics, !assertion && p.text != null) {
            observedUltronOperation("automator", kind!!, diagnostics, !assertion && p.text != null) {
                var value: Any? = null
                var ultron = UltronUiObject2.by(selector).withResultHandler(::handleUltronResult)
                p.timeoutMs?.let { ultron = ultron.withTimeout(it) }
                fun matches() = getUiDevice().findObjects(selector)
                fun target(): UiObject2 {
                    val matches = matches()
                    if (p.index != null) return matches.getOrNull(p.index)
                        ?: throw UiObjectNotFoundException("No element at index ${p.index}")
                    if (matches.isEmpty()) throw UiObjectNotFoundException("No element for the Automator selector")
                    check(matches.size == 1) { "Expected one Automator element, found ${matches.size}" }
                    return matches.single()
                }
                if (assertion) {
                    ultron.executeAssertion(assertionBlock = {
                        when (kind) {
                            "notExists" -> if (p.index == null) matches().isEmpty() else matches().getOrNull(p.index) == null
                            "exists" -> { target(); true }
                            "textEquals" -> target().text == p.text
                            "textContains" -> target().text?.contains(p.text!!) == true
                            else -> booleanValue(target(), kind) == (p.expected ?: true)
                        }
                    }, name = kind, description = kind, type = CommonOperationType.DEFAULT)
                } else {
                    ultron.executeAction(actionBlock = {
                        val target = target() // Re-resolve inside every Ultron attempt.
                        value = when (kind) {
                            "click" -> { target.click(); null }
                            "longClick" -> { target.longClick(); null }
                            "clearText" -> { target.clear(); null }
                            "replaceText" -> { target.text = p.text!!; null }
                            "text" -> target.text
                            else -> booleanValue(target, kind)
                        }
                    }, name = kind, description = kind, type = CommonOperationType.DEFAULT)
                }
                value
            }
        }
    }

    private fun selector(strategy: String?, locator: String): BySelector = when (strategy) {
        "clazz" -> By.clazz(locator)
        "res" -> By.res(locator)
        "text" -> By.text(locator)
        "textContains" -> By.textContains(locator)
        "textStartsWith" -> By.textStartsWith(locator)
        "textEndsWith" -> By.textEndsWith(locator)
        "desc" -> By.desc(locator)
        "descContains" -> By.descContains(locator)
        "descStartsWith" -> By.descStartsWith(locator)
        "descEndsWith" -> By.descEndsWith(locator)
        "pkg" -> By.pkg(locator)
        else -> invalidOperation("Unsupported Automator strategy")
    }

    private fun booleanValue(target: UiObject2, kind: String): Boolean = when (kind) {
        "enabled" -> target.isEnabled
        "checked" -> target.isChecked
        "selected" -> target.isSelected
        "displayed" -> !target.visibleBounds.isEmpty
        else -> error("Unsupported boolean property")
    }

    companion object {
        private val BOOLEAN_ASSERTIONS = setOf("enabled", "checked", "selected", "displayed")
        private val TEXT_ASSERTIONS = setOf("textEquals", "textContains")
        private val ASSERTIONS = BOOLEAN_ASSERTIONS + TEXT_ASSERTIONS + setOf("exists", "notExists")
        private val ACTIONS = BOOLEAN_ASSERTIONS + setOf("click", "longClick", "clearText", "replaceText", "text")
    }
}

internal fun invalidOperation(message: String): Nothing = throw InvalidArgumentException(message)

internal fun validateOperationTimeout(timeout: Long?) {
    if (timeout != null && timeout !in 0..60000) invalidOperation("timeoutMs must be 0..60000")
}

/** Strict raw JSON validation before Gson can coerce strings, booleans or fractional numbers. */
internal fun validateOperationJson(body: String, strings: Set<String>, booleans: Set<String> = emptySet(),
    integers: Set<String> = emptySet(), objects: Set<String> = emptySet(), arrays: Set<String> = emptySet()) {
    val root = com.google.gson.JsonParser.parseString(body)
    if (!root.isJsonObject) invalidOperation("Operation parameters must be an object")
    for ((key, value) in root.asJsonObject.entrySet()) {
        val valid = when (key) {
            in strings -> value.isJsonPrimitive && value.asJsonPrimitive.isString
            in booleans -> value.isJsonPrimitive && value.asJsonPrimitive.isBoolean
            in integers -> value.isJsonPrimitive && value.asJsonPrimitive.isNumber &&
                runCatching { value.asBigDecimal.intValueExact() }.isSuccess
            in objects -> value.isJsonObject
            in arrays -> value.isJsonArray
            else -> false
        }
        if (!valid) invalidOperation("Invalid operation parameter: $key")
    }
}
