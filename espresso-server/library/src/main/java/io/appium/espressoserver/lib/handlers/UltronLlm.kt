package io.appium.espressoserver.lib.handlers

import android.view.KeyEvent
import android.view.View
import androidx.test.espresso.Espresso.onView
import com.atiurin.ultron.core.espresso.action.EspressoActionType
import com.google.gson.JsonObject
import io.appium.espressoserver.lib.handlers.exceptions.InvalidArgumentException
import io.appium.espressoserver.lib.helpers.ultron
import io.appium.espressoserver.lib.llm.UltronOperationInfo
import io.appium.espressoserver.lib.llm.UltronSnapshotResult
import io.appium.espressoserver.lib.llm.UltronSnapshots
import io.appium.espressoserver.lib.llm.SnapshotCapture
import io.appium.espressoserver.lib.llm.ViewSnapshot
import io.appium.espressoserver.lib.model.AppiumParams
import io.appium.espressoserver.lib.model.ScopedMatcher
import io.appium.espressoserver.lib.model.toJsonMatcher
import io.appium.espressoserver.lib.viewaction.PressKeyWithOptions
import org.hamcrest.Matcher

class UltronSnapshotParams : AppiumParams() {
    val maxDepth: Int = 100
    val maxNodes: Int = 5000
    val useUnmergedTree: Boolean = true
    val mode: String? = null

    companion object {
        fun validateJson(body: String) {
            val root = com.google.gson.JsonParser.parseString(body)
            if (!root.isJsonObject) throw InvalidArgumentException("Snapshot parameters must be an object")
            for ((key, value) in root.asJsonObject.entrySet()) {
                val valid = when (key) {
                    "mode" -> value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString in setOf("ultron", "automator")
                    "useUnmergedTree" -> value.isJsonPrimitive && value.asJsonPrimitive.isBoolean
                    "maxDepth", "maxNodes" -> value.isJsonPrimitive && value.asJsonPrimitive.isNumber &&
                        runCatching { value.asBigDecimal.intValueExact() }.isSuccess
                    else -> false
                }
                if (!valid) throw InvalidArgumentException("Invalid snapshot parameter: $key")
            }
        }
    }
}

class UltronOperationParams : AppiumParams() {
    val matcher: JsonObject? = null
    val action: String? = null
    val text: String? = null
    val condition: JsonObject? = null
    val exists: Boolean = true
    val timeoutMs: Long? = null
    val offsetX: Int? = null
    val offsetY: Int? = null
    val keyCode: Int? = null
    val metaState: Int? = null
    val flags: Int? = null
    val autoScroll: Boolean? = null
    val name: String? = null

    fun validateAction() {
        if (action !in ACTIONS) throw InvalidArgumentException("action must be one of $ACTIONS")
        if (action in setOf("typeText", "replaceText") && text == null) {
            throw InvalidArgumentException("text is required for $action")
        }
        if (offsetX != null && action !in X_OFFSET_ACTIONS) {
            throw InvalidArgumentException("offsetX is supported by corner and center-left/right clicks only")
        }
        if (offsetY != null && action !in Y_OFFSET_ACTIONS) {
            throw InvalidArgumentException("offsetY is supported by corner and top/bottom-center clicks only")
        }
        if (action == "pressKey" && keyCode == null) throw InvalidArgumentException("keyCode is required for pressKey")
        if (action == "pressKey" && keyCode != null && keyCode !in 1 until KeyEvent.getMaxKeyCode()) {
            throw InvalidArgumentException("keyCode must be greater than 0 and less than ${KeyEvent.getMaxKeyCode()}")
        }
        if (action != "pressKey" && (keyCode != null || metaState != null || flags != null)) {
            throw InvalidArgumentException("keyCode, metaState and flags are supported by pressKey only")
        }
        if (listOf(keyCode, metaState, flags).any { it != null && it < 0 }) {
            throw InvalidArgumentException("keyCode, metaState and flags must be non-negative integers")
        }
        if (name != null && name.isEmpty()) throw InvalidArgumentException("name must not be empty")
    }

    companion object {
        private val CORNER_ACTIONS = setOf("clickTopLeft", "clickTopRight", "clickBottomRight", "clickBottomLeft")
        private val X_OFFSET_ACTIONS = CORNER_ACTIONS + setOf("clickCenterLeft", "clickCenterRight")
        private val Y_OFFSET_ACTIONS = CORNER_ACTIONS + setOf("clickTopCenter", "clickBottomCenter")
        private val ACTIONS = X_OFFSET_ACTIONS + Y_OFFSET_ACTIONS + setOf(
            "click", "doubleClick", "longClick", "typeText", "replaceText", "clearText", "scrollTo",
            "swipeUp", "swipeDown", "swipeLeft", "swipeRight", "pressKey", "closeSoftKeyboard",
        )
    }
}

class UltronSnapshot : RequestHandler<UltronSnapshotParams, ViewSnapshot> {
    override fun handleInternal(params: UltronSnapshotParams): ViewSnapshot {
        if (params.maxDepth !in 0..100 || params.maxNodes !in 1..10000) {
            throw InvalidArgumentException("maxDepth must be 0..100; maxNodes must be 1..10000")
        }
        return SnapshotCapture.capture(params.maxDepth, params.maxNodes, params.useUnmergedTree,
            params.mode ?: SnapshotCapture.mode)
    }
}

class UltronLastResult : RequestHandler<AppiumParams, UltronSnapshotResult?> {
    override fun handleInternal(params: AppiumParams) = UltronSnapshots.listener.latest
}

/** Explicit Espresso entry point: no global backend switch and no change to W3C command results. */
class UltronLlmOperation(private val assertion: Boolean) : RequestHandler<UltronOperationParams, UltronSnapshotResult> {
    override fun handleInternal(params: UltronOperationParams): UltronSnapshotResult = prepare(params).invoke()

    internal fun prepare(params: UltronOperationParams): () -> UltronSnapshotResult {
        val matcher = (params.matcher ?: throw InvalidArgumentException("matcher is required")).toString().toJsonMatcher().query
        val timeout = params.timeoutMs
        if (timeout != null && timeout !in 0..60000) throw InvalidArgumentException("timeoutMs must be 0..60000")
        val condition = params.condition?.toString()?.toJsonMatcher()?.query
        if (!assertion && condition != null) throw InvalidArgumentException("condition is supported by ultronAssert only")
        if (condition?.scope != null) throw InvalidArgumentException("Use matcher.scope for window selection, not condition.scope")
        if (assertion && !params.exists && condition != null) throw InvalidArgumentException("condition cannot be combined with exists=false")
        if (!assertion) params.validateAction()

        return { executePrepared(params, matcher, condition) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun executePrepared(params: UltronOperationParams, matcher: ScopedMatcher, condition: ScopedMatcher?): UltronSnapshotResult {
        val timeout = params.timeoutMs
        val listener = UltronSnapshots.listener
        listener.beginCommand()
        val start = android.os.SystemClock.elapsedRealtime()
        try {
            var interaction = onView(matcher.matcher as Matcher<View>)
            matcher.scope?.let { interaction = interaction.inRoot(it as Matcher<androidx.test.espresso.Root>) }
            var ultron = interaction.ultron()
            timeout?.let { ultron = ultron.withTimeout(it) }
            // withTimeout creates a new interaction; apply mutable options afterwards.
            if (!assertion) {
                params.name?.let { ultron.withName(it) }
                params.autoScroll?.let { ultron.withAutoScroll(it) }
            }
            if (assertion) {
                when {
                    !params.exists -> ultron.doesNotExist()
                    condition != null -> ultron.assertMatches(condition.matcher as Matcher<View>)
                    else -> ultron.exists()
                }
            } else {
                when (params.action) {
                    "click" -> ultron.click()
                    "doubleClick" -> ultron.doubleClick()
                    "longClick" -> ultron.longClick()
                    "clickTopLeft" -> ultron.clickTopLeft(params.offsetX ?: 0, params.offsetY ?: 0)
                    "clickTopCenter" -> ultron.clickTopCenter(params.offsetY ?: 0)
                    "clickTopRight" -> ultron.clickTopRight(params.offsetX ?: 0, params.offsetY ?: 0)
                    "clickCenterRight" -> ultron.clickCenterRight(params.offsetX ?: 0)
                    "clickBottomRight" -> ultron.clickBottomRight(params.offsetX ?: 0, params.offsetY ?: 0)
                    "clickBottomCenter" -> ultron.clickBottomCenter(params.offsetY ?: 0)
                    "clickBottomLeft" -> ultron.clickBottomLeft(params.offsetX ?: 0, params.offsetY ?: 0)
                    "clickCenterLeft" -> ultron.clickCenterLeft(params.offsetX ?: 0)
                    "pressKey" -> if (params.metaState == null && params.flags == null) {
                        ultron.pressKey(params.keyCode!!)
                    } else {
                        val keyAction = PressKeyWithOptions(params.keyCode!!, params.metaState, params.flags)
                        ultron.executeAction(
                            operationBlock = ultron.getInteractionActionBlock(keyAction),
                            name = "PressKey code '${params.keyCode}' on '${ultron.elementInfo.name}'",
                            type = EspressoActionType.PRESS_KEY,
                            description = keyAction.description,
                        )
                    }
                    "closeSoftKeyboard" -> ultron.closeSoftKeyboard()
                    "typeText" -> ultron.typeText(params.text!!)
                    "replaceText" -> ultron.replaceText(params.text!!)
                    "clearText" -> ultron.clearText()
                    "scrollTo" -> ultron.scrollTo()
                    "swipeUp" -> ultron.swipeUp()
                    "swipeDown" -> ultron.swipeDown()
                    "swipeLeft" -> ultron.swipeLeft()
                    "swipeRight" -> ultron.swipeRight()
                }
            }
            return listener.commandResult() ?: fallback("success", null, start, params.text != null)
        } catch (error: Throwable) {
            if (error !is Exception && error !is AssertionError) throw error
            val observed = listener.commandResult()
            return if (observed != null) observed.copy(operation = observed.operation.copy(status = "failure", error = error.toString()))
                .redactSensitiveDetails(params.text != null)
            else fallback("failure", error, start, params.text != null)
        } finally { listener.endCommand() }
    }

    private fun fallback(status: String, error: Throwable?, start: Long, textInput: Boolean) = UltronSnapshotResult(
        UltronOperationInfo(status, if (assertion) "assert" else "act", "espresso",
            android.os.SystemClock.elapsedRealtime() - start, error?.toString()),
        UltronSnapshots.captureForOperation(),
    ).redactSensitiveDetails(textInput)
}
