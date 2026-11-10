package io.appium.espressoserver.lib.compose

import androidx.compose.ui.test.*
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.appium.espressoserver.lib.handlers.exceptions.InvalidArgumentException
import io.appium.espressoserver.lib.llm.ComposeSnapshotBridge

/** A bounded, explicit JSON DSL. Never reflects or evaluates caller-supplied Kotlin code. */
internal object ComposeMatchers {
    fun parse(json: JsonObject): SemanticsMatcher {
        var count = 0
        fun node(value: JsonElement, depth: Int): SemanticsMatcher {
            if (depth > 16 || ++count > 128) invalid("Matcher exceeds depth 16 or 128 nodes")
            if (!value.isJsonObject) invalid("Matcher must be an object")
            val obj = value.asJsonObject
            if (obj.keySet().any { it !in setOf("name", "args") }) invalid("Compose matchers accept only name and args; use relationships to scope a target")
            val name = obj["name"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?: invalid("Matcher name must be a string")
            val args = obj["args"]?.let { if (!it.isJsonArray) invalid("Matcher args must be an array"); it.asJsonArray.toList() } ?: emptyList()
            fun arity(min: Int, max: Int = min) { if (args.size !in min..max) invalid("$name expects $min..$max arguments") }
            fun string(i: Int): String = args.getOrNull(i)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?: invalid("$name argument $i must be a string")
            fun bool(i: Int): Boolean = args.getOrNull(i)?.let {
                if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) invalid("$name argument $i must be a boolean")
                it.asBoolean
            } ?: false
            fun child(i: Int) = node(args[i], depth + 1)
            return when (name) {
                "hasTestTag" -> { arity(1); hasTestTag(string(0)) }
                "hasText" -> { arity(1, 3); hasText(string(0), substring = bool(1), ignoreCase = bool(2)) }
                "hasContentDescription" -> { arity(1, 3); hasContentDescription(string(0), substring = bool(1), ignoreCase = bool(2)) }
                "allOf", "anyOf" -> {
                    arity(1, 32)
                    args.indices.map(::child).reduce { a, b -> if (name == "allOf") a and b else a or b }
                }
                "not" -> { arity(1); !child(0) }
                "hasParent" -> { arity(1); hasParent(child(0)) }
                "hasAnyAncestor" -> { arity(1); hasAnyAncestor(child(0)) }
                "hasAnyDescendant" -> { arity(1); hasAnyDescendant(child(0)) }
                "hasAnySibling" -> { arity(1); hasAnySibling(child(0)) }
                "propertyEquals" -> {
                    arity(2)
                    val property = string(0)
                    SemanticsMatcher("$property equals ${args[1]}") { semantics ->
                        val entry = semantics.config.toList().singleOrNull { it.key.name == property }
                        entry != null && Gson().toJsonTree(ComposeSnapshotBridge.jsonValue(entry.value)) == args[1]
                    }
                }
                "hasAction" -> {
                    arity(1)
                    val action = string(0)
                    SemanticsMatcher("has action $action") { semantics -> semantics.config.toList().any {
                        it.key.name == action && it.value is androidx.compose.ui.semantics.AccessibilityAction<*>
                    } }
                }
                else -> {
                    arity(0)
                    when (name) {
                        "isRoot" -> isRoot()
                        "isEnabled" -> isEnabled()
                        "isNotEnabled" -> isNotEnabled()
                        "isFocused" -> isFocused()
                        "isNotFocused" -> isNotFocused()
                        "isSelected" -> isSelected()
                        "isNotSelected" -> isNotSelected()
                        "isSelectable" -> isSelectable()
                        "isToggleable" -> isToggleable()
                        "isOn" -> isOn()
                        "isOff" -> isOff()
                        "hasClickAction" -> hasClickAction()
                        "hasScrollAction" -> hasScrollAction()
                        "hasSetTextAction" -> hasSetTextAction()
                        else -> invalid("Unsupported Compose matcher: $name")
                    }
                }
            }
        }
        return node(json, 0)
    }

    private fun invalid(message: String): Nothing = throw InvalidArgumentException(message)
}
