package io.appium.espressoserver.lib.compose

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import com.atiurin.ultron.core.common.Operation
import com.atiurin.ultron.core.common.options.ClickOption
import com.atiurin.ultron.core.common.options.DoubleClickOption
import com.atiurin.ultron.core.common.options.LongClickOption
import com.atiurin.ultron.core.common.options.TextContainsOption
import com.atiurin.ultron.core.common.options.TextEqualsOption
import com.atiurin.ultron.core.common.options.ContentDescriptionContainsOption
import com.atiurin.ultron.core.compose.nodeinteraction.*
import com.atiurin.ultron.core.compose.operation.UltronComposeOperation
import com.atiurin.ultron.core.compose.option.ComposeSwipeOption
import com.google.gson.JsonObject
import io.appium.espressoserver.lib.drivers.DriverContext
import io.appium.espressoserver.lib.drivers.composeTestRule
import io.appium.espressoserver.lib.handlers.UltronComposeParams
import io.appium.espressoserver.lib.handlers.exceptions.InvalidArgumentException
import io.appium.espressoserver.lib.helpers.handleUltronResult
import io.appium.espressoserver.lib.llm.UltronOperationInfo
import io.appium.espressoserver.lib.llm.UltronSnapshotResult
import io.appium.espressoserver.lib.llm.UltronSnapshots

internal object ComposeOperationBridge {
    fun isOperation(operation: Operation): Boolean = operation is UltronComposeOperation
    fun waitForIdle() = DriverContext.composeTestRule.waitForIdle()

    @OptIn(ExperimentalTestApi::class)
    fun execute(params: UltronComposeParams, assertion: Boolean): UltronSnapshotResult {
        val matcher = ComposeMatchers.parse(params.matcher ?: invalid("matcher is required"))
        val item = params.itemMatcher?.let(ComposeMatchers::parse)
        val condition = params.condition?.let(ComposeMatchers::parse)
        val o = Options(params.options)
        validate(params, assertion, o)
        // Initialize the existing shared rule before Ultron obtains its provider. Never create a second rule.
        val rule = DriverContext.composeTestRule
        val listener = UltronSnapshots.listener
        listener.beginCommand(params.useUnmergedTree)
        val start = android.os.SystemClock.elapsedRealtime()
        try {
            // onNode re-evaluates the matcher on each operation/retry and requires a unique match.
            var u = UltronComposeSemanticsNodeInteraction(
                semanticsNodeInteractionProvider = { rule.onNode(matcher, params.useUnmergedTree) },
                resultHandler = ::handleUltronResult,
            )
            params.timeoutMs?.let { u = u.withTimeout(it) }
            params.name?.let { u.withName(it) }
            if (assertion) {
                when (params.assertion ?: "exists") {
                    "exists" -> u.assertExists()
                    "doesNotExist" -> u.assertDoesNotExist()
                    "isDisplayed" -> u.assertIsDisplayed()
                    "isNotDisplayed" -> u.assertIsNotDisplayed()
                    "isEnabled" -> u.assertIsEnabled()
                    "isNotEnabled" -> u.assertIsNotEnabled()
                    "isFocused" -> u.assertIsFocused()
                    "isNotFocused" -> u.assertIsNotFocused()
                    "isSelected" -> u.assertIsSelected()
                    "isNotSelected" -> u.assertIsNotSelected()
                    "isSelectable" -> u.assertIsSelectable()
                    "isToggleable" -> u.assertIsToggleable()
                    "isOn" -> u.assertIsOn()
                    "isOff" -> u.assertIsOff()
                    "isIndeterminate" -> u.assertIsIndeterminate()
                    "hasClickAction" -> u.assertHasClickAction()
                    "hasNoClickAction" -> u.assertHasNoClickAction()
                    "textEquals" -> u.assertTextEquals(*params.expected!!.toTypedArray(), option = TextEqualsOption(includeEditableText = o.bool("includeEditableText", true)))
                    "textContains" -> u.assertTextContains(params.text!!, TextContainsOption(substring = o.bool("substring"), ignoreCase = o.bool("ignoreCase")))
                    "contentDescriptionEquals" -> u.assertContentDescriptionEquals(*params.expected!!.toTypedArray())
                    "contentDescriptionContains" -> u.assertContentDescriptionContains(params.text!!, ContentDescriptionContainsOption(substring = o.bool("substring"), ignoreCase = o.bool("ignoreCase")))
                    "valueEquals" -> u.assertValueEquals(params.text!!)
                    "rangeInfoEquals" -> u.assertRangeInfoEquals(ProgressBarRangeInfo(o.float("current"), o.float("min")..o.float("max"), o.int("steps", 0)))
                    "widthIsAtLeast" -> u.assertWidthIsAtLeast(Dp(o.float("dp")))
                    "widthIsEqualTo" -> u.assertWidthIsEqualTo(Dp(o.float("dp")))
                    "heightIsAtLeast" -> u.assertHeightIsAtLeast(Dp(o.float("dp")))
                    "heightIsEqualTo" -> u.assertHeightIsEqualTo(Dp(o.float("dp")))
                    "matches" -> u.assertMatches(condition!!)
                }
            } else {
                val click = ClickOption(o.long("offsetX", 0), o.long("offsetY", 0))
                when (params.action) {
                    "click" -> when (o.string("position", "center")) {
                        "topLeft" -> u.clickTopLeft(click)
                        "topCenter" -> u.clickTopCenter(click)
                        "topRight" -> u.clickTopRight(click)
                        "centerLeft" -> u.clickCenterLeft(click)
                        "centerRight" -> u.clickCenterRight(click)
                        "bottomLeft" -> u.clickBottomLeft(click)
                        "bottomCenter" -> u.clickBottomCenter(click)
                        "bottomRight" -> u.clickBottomRight(click)
                        else -> u.click(click)
                    }
                    "longClick" -> {
                        val option = LongClickOption(click.xOffset, click.yOffset, o.optionalLong("durationMs"))
                        listOf(u::longClick, u::longClickTopLeft, u::longClickTopCenter, u::longClickTopRight,
                            u::longClickCenterLeft, u::longClickCenterRight, u::longClickBottomLeft, u::longClickBottomCenter, u::longClickBottomRight)[o.position()](option)
                    }
                    "doubleClick" -> {
                        val option = DoubleClickOption(click.xOffset, click.yOffset, o.optionalLong("delayMs"))
                        listOf(u::doubleClick, u::doubleClickTopLeft, u::doubleClickTopCenter, u::doubleClickTopRight,
                            u::doubleClickCenterLeft, u::doubleClickCenterRight, u::doubleClickBottomLeft, u::doubleClickBottomCenter, u::doubleClickBottomRight)[o.position()](option)
                    }
                    "inputText", "typeText" -> u.inputText(params.text!!)
                    "replaceText" -> u.replaceText(params.text!!)
                    "setText" -> u.setText(params.text!!)
                    "clearText" -> u.clearText()
                    "imeAction" -> u.imeAction()
                    "scrollTo" -> u.scrollTo()
                    "scrollToIndex" -> u.scrollToIndex(o.int("index"))
                    "scrollToKey" -> u.scrollToKey(o.key())
                    "scrollToNode" -> u.scrollToNode(item!!)
                    "swipeUp" -> u.swipeUp(o.swipe())
                    "swipeDown" -> u.swipeDown(o.swipe())
                    "swipeLeft" -> u.swipeLeft(o.swipe())
                    "swipeRight" -> u.swipeRight(o.swipe())
                    "swipe" -> u.swipe(o.swipe())
                    "expand" -> u.expand()
                    "collapse" -> u.collapse()
                    "dismiss" -> u.dismiss()
                    "setProgress" -> u.setProgress(o.float("value"))
                    "customAccessibilityAction" -> u.performCustomAccessibilityActionWithLabel(o.string("label"))
                    "selectText" -> u.selectText(TextRange(o.int("start"), o.int("end")))
                    "setSelection" -> u.setSelection(o.int("start"), o.int("end"), o.bool("traversalMode"))
                    "copyText" -> u.copyText()
                    "cutText" -> u.cutText()
                    "pasteText" -> u.pasteText()
                    "pressKey" -> u.pressKey(KeyEvent(AndroidKeyEvent(0, 0,
                        if (o.string("keyAction", "down") == "up") AndroidKeyEvent.ACTION_UP else AndroidKeyEvent.ACTION_DOWN,
                        o.int("keyCode"), 0, o.int("metaState", 0))))
                }
            }
            return listener.commandResult() ?: fallback(params, assertion, "success", null, start).also(listener::record)
        } catch (error: Throwable) {
            if (error !is Exception && error !is AssertionError) throw error
            val observed = listener.commandResult()
            val result = observed?.copy(operation = observed.operation.copy(status = "failure", error = error.toString()))
                ?: fallback(params, assertion, "failure", error, start)
            val safe = result.redactSensitiveDetails(params.text != null)
            listener.record(safe)
            return safe
        } finally { listener.endCommand() }
    }

    private fun fallback(p: UltronComposeParams, assertion: Boolean, status: String, error: Throwable?, start: Long) = UltronSnapshotResult(
        UltronOperationInfo(status, p.name ?: if (assertion) p.assertion ?: "exists" else p.action!!,
            if (assertion) "assert" else "act", android.os.SystemClock.elapsedRealtime() - start, error?.toString(), "compose"),
        UltronSnapshots.captureForOperation(),
    ).redactSensitiveDetails(p.text != null)

    private val states = setOf("exists", "doesNotExist", "isDisplayed", "isNotDisplayed", "isEnabled", "isNotEnabled", "isFocused", "isNotFocused",
        "isSelected", "isNotSelected", "isSelectable", "isToggleable", "isOn", "isOff", "isIndeterminate", "hasClickAction", "hasNoClickAction")
    private val textActions = setOf("inputText", "typeText", "replaceText", "setText")
    private val contains = setOf("textContains", "contentDescriptionContains")
    private val sizes = setOf("widthIsAtLeast", "widthIsEqualTo", "heightIsAtLeast", "heightIsEqualTo")
    private val swipes = setOf("swipe", "swipeUp", "swipeDown", "swipeLeft", "swipeRight")

    private fun validate(p: UltronComposeParams, assertion: Boolean, o: Options) {
        if (p.timeoutMs != null && p.timeoutMs !in 0..60000) invalid("timeoutMs must be 0..60000")
        if (p.name != null && p.name.isEmpty()) invalid("name must not be empty")
        val op = if (assertion) p.assertion ?: "exists" else p.action ?: invalid("action is required")
        if (assertion && (p.action != null || p.itemMatcher != null)) invalid("action/itemMatcher are not assertion arguments")
        if (!assertion && (p.assertion != null || p.expected != null || p.condition != null)) invalid("assertion/expected/condition are not action arguments")
        val allowed = if (assertion) when {
            op in states -> emptySet()
            op == "textEquals" -> setOf("includeEditableText")
            op in contains -> setOf("substring", "ignoreCase")
            op in setOf("contentDescriptionEquals", "valueEquals", "matches") -> emptySet()
            op == "rangeInfoEquals" -> setOf("current", "min", "max", "steps")
            op in sizes -> setOf("dp")
            else -> invalid("Unsupported Compose assertion: $op")
        } else when {
            op == "click" -> setOf("position", "offsetX", "offsetY")
            op == "longClick" -> setOf("position", "offsetX", "offsetY", "durationMs")
            op == "doubleClick" -> setOf("position", "offsetX", "offsetY", "delayMs")
            op in swipes -> setOf("startXOffset", "endXOffset", "startYOffset", "endYOffset", "durationMs")
            op == "scrollToIndex" -> setOf("index")
            op == "scrollToKey" -> setOf("key", "keyType")
            op == "setProgress" -> setOf("value")
            op == "customAccessibilityAction" -> setOf("label")
            op == "selectText" -> setOf("start", "end")
            op == "setSelection" -> setOf("start", "end", "traversalMode")
            op == "pressKey" -> setOf("keyCode", "metaState", "keyAction")
            op in textActions + setOf("clearText", "imeAction", "scrollTo", "scrollToNode", "expand", "collapse", "dismiss", "copyText", "cutText", "pasteText") -> emptySet()
            else -> invalid("Unsupported Compose action: $op")
        }
        if (p.options.keySet().any { it !in allowed }) invalid("options for $op may contain only $allowed")
        val needsText = if (assertion) op in contains + "valueEquals" else op in textActions
        if ((p.text != null) != needsText) invalid("text is required only for text actions/contains/valueEquals")
        val needsExpected = assertion && op in setOf("textEquals", "contentDescriptionEquals")
        if ((p.expected != null) != needsExpected) invalid("expected is required only for textEquals/contentDescriptionEquals")
        if ((p.itemMatcher != null) != (!assertion && op == "scrollToNode")) invalid("itemMatcher is required only for scrollToNode")
        if ((p.condition != null) != (assertion && op == "matches")) invalid("condition is required only for matches")
        for (key in p.options.keySet()) when (key) {
            "offsetX", "offsetY" -> o.int(key)
            "start", "end", "index", "steps", "metaState" -> if (o.int(key) < 0) invalid("$key must be non-negative")
            "durationMs", "delayMs" -> if (o.long(key) !in 1..60000) invalid("$key must be 1..60000")
            "substring", "ignoreCase", "includeEditableText", "traversalMode" -> o.bool(key)
            "position" -> if (o.string(key) !in setOf("center", "topLeft", "topCenter", "topRight", "centerLeft", "centerRight", "bottomLeft", "bottomCenter", "bottomRight")) invalid("Unknown click position")
            "label" -> if (o.string(key).isEmpty()) invalid("label must not be empty")
            "keyAction" -> if (o.string(key) !in setOf("down", "up")) invalid("keyAction must be down or up")
            "keyCode" -> if (o.int(key) !in 1 until AndroidKeyEvent.getMaxKeyCode()) invalid("Invalid Android keyCode")
            "key", "keyType" -> Unit // Validated together below.
            else -> o.float(key)
        }
        when (op) {
            "scrollToIndex" -> o.int("index")
            "scrollToKey" -> o.key()
            "setProgress" -> o.float("value")
            "customAccessibilityAction" -> o.string("label")
            "selectText", "setSelection" -> { o.int("start"); o.int("end"); if (op == "setSelection") o.boolRequired("traversalMode") }
            "pressKey" -> o.int("keyCode")
            "rangeInfoEquals" -> {
                val min = o.float("min"); val max = o.float("max"); val current = o.float("current")
                if (min > max || current !in min..max) invalid("Expected min <= current <= max")
            }
            in sizes -> if (o.float("dp") < 0) invalid("dp must be non-negative")
        }
    }

    private class Options(private val json: JsonObject) {
        fun optionalLong(key: String): Long? = if (json.has(key)) long(key) else null
        fun position(): Int = listOf("center", "topLeft", "topCenter", "topRight", "centerLeft", "centerRight", "bottomLeft", "bottomCenter", "bottomRight").indexOf(string("position", "center"))
        private fun primitive(key: String) = json[key]?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: invalid("options.$key is required and must be a primitive")
        fun string(key: String, default: String? = null): String {
            if (!json.has(key) && default != null) return default
            return primitive(key).takeIf { it.isString }?.asString ?: invalid("options.$key must be a string")
        }
        fun long(key: String, default: Long? = null): Long {
            if (!json.has(key) && default != null) return default
            val value = primitive(key)
            if (!value.isNumber) invalid("options.$key must be an integer")
            return try { value.asBigDecimal.longValueExact() } catch (_: ArithmeticException) { invalid("options.$key must be an integer in Long range") }
        }
        fun int(key: String, default: Int? = null): Int {
            val value = long(key, default?.toLong())
            if (value !in Int.MIN_VALUE..Int.MAX_VALUE) invalid("options.$key must fit Int")
            return value.toInt()
        }
        fun float(key: String, default: Float? = null): Float {
            if (!json.has(key) && default != null) return default
            val value = primitive(key)
            if (!value.isNumber) invalid("options.$key must be a finite number")
            return value.asFloat.also { if (!it.isFinite()) invalid("options.$key must be a finite Float") }
        }
        fun bool(key: String, default: Boolean = false): Boolean = if (!json.has(key)) default else boolRequired(key)
        fun boolRequired(key: String): Boolean = primitive(key).takeIf { it.isBoolean }?.asBoolean ?: invalid("options.$key must be a boolean")
        fun key(): Any = when (string("keyType", "string")) {
            "string" -> string("key")
            "int" -> int("key")
            "long" -> string("key").toLongOrNull() ?: invalid("Long keys must be decimal strings in Long range")
            else -> invalid("keyType must be string, int or long")
        }
        fun swipe() = ComposeSwipeOption(float("startXOffset", 0f), float("endXOffset", 0f), float("startYOffset", 0f), float("endYOffset", 0f), long("durationMs", ComposeSwipeOption().durationMs))
    }

    private fun invalid(message: String): Nothing = throw InvalidArgumentException(message)
}
