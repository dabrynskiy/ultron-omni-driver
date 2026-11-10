package io.appium.espressoserver.lib.handlers

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonParseException
import io.appium.espressoserver.lib.compose.ComposeOperationBridge
import io.appium.espressoserver.lib.llm.UltronSnapshotResult
import io.appium.espressoserver.lib.model.AppiumParams

/** Explicit Compose commands, independent of the standard WebDriver subdriver setting. */
class UltronComposeParams : AppiumParams() {
    val matcher: JsonObject? = null
    val action: String? = null
    val assertion: String? = null
    val itemMatcher: JsonObject? = null
    val condition: JsonObject? = null
    val text: String? = null
    val expected: List<String>? = null
    val options: JsonObject = JsonObject()
    val useUnmergedTree: Boolean = true
    val timeoutMs: Long? = null
    val name: String? = null

    companion object {
        /** Gson normally coerces numbers to strings and string booleans; reject those over HTTP. */
        fun validateJson(body: String) {
            val root = JsonParser.parseString(body)
            if (!root.isJsonObject) throw JsonParseException("Compose parameters must be an object")
            val strings = setOf("action", "assertion", "text", "name")
            val objects = setOf("matcher", "itemMatcher", "condition", "options")
            for ((key, value) in root.asJsonObject.entrySet()) {
                val valid = when (key) {
                    in strings -> value.isJsonPrimitive && value.asJsonPrimitive.isString
                    in objects -> value.isJsonObject
                    "expected" -> value.isJsonArray && value.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    "useUnmergedTree" -> value.isJsonPrimitive && value.asJsonPrimitive.isBoolean
                    "timeoutMs" -> value.isJsonPrimitive && value.asJsonPrimitive.isNumber && try {
                        value.asBigDecimal.longValueExact() in 0..60000
                    } catch (_: ArithmeticException) { false }
                    else -> false
                }
                if (!valid) throw JsonParseException("Invalid Compose parameter: $key")
            }
        }
    }
}

class UltronComposeOperation(private val assertion: Boolean) : RequestHandler<UltronComposeParams, UltronSnapshotResult> {
    override fun handleInternal(params: UltronComposeParams): UltronSnapshotResult =
        ComposeOperationBridge.execute(params, assertion)
}
