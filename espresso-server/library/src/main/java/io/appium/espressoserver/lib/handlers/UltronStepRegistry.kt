package io.appium.espressoserver.lib.handlers

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.appium.espressoserver.lib.compose.ComposeOperationBridge
import io.appium.espressoserver.lib.handlers.exceptions.InvalidArgumentException

/** Command names are the single discriminator; adapters prepare without resolving UI targets. */
internal object UltronStepRegistry {
    private val gson = Gson()
    private val adapters: Map<String, (JsonObject) -> UltronPreparedOperation> = mapOf<String, (JsonObject) -> UltronPreparedOperation>(
        "ultronAutomatorAct" to { p -> prepareAutomator(p, false) },
        "ultronAutomatorAssert" to { p -> prepareAutomator(p, true) },
        "ultronWebAct" to { p -> prepareWeb(p, false) },
        "ultronWebAssert" to { p -> prepareWeb(p, true) },
    ) + setOf("ultronAct", "ultronAssert", "ultronComposeAct", "ultronComposeAssert", "ultronRecycler",
        "ultronBack", "ultronHideKeyboard", "ultronElement").associateWith { command ->
        { p: JsonObject -> prepareExisting(command, p) }
    }

    fun prepare(command: String, params: JsonObject, version: Int): UltronPreparedOperation {
        if (version == 1 && command in setOf("ultronAutomatorAct", "ultronAutomatorAssert", "ultronWebAct", "ultronWebAssert"))
            invalid("$command requires schemaVersion 2")
        return (adapters[command] ?: invalid("Unsupported batch command: $command"))(params)
    }

    private fun prepareAutomator(p: JsonObject, assertion: Boolean): UltronPreparedOperation {
        UltronAutomatorParams.validateJson(p.toString())
        return UltronAutomatorOperation(assertion).prepare(gson.fromJson(p, UltronAutomatorParams::class.java))
    }

    private fun prepareWeb(p: JsonObject, assertion: Boolean): UltronPreparedOperation {
        UltronWebParams.validateJson(p.toString())
        return UltronWebOperation(assertion).prepare(gson.fromJson(p, UltronWebParams::class.java))
    }

    private fun prepareExisting(command: String, p: JsonObject): UltronPreparedOperation {
        val compose = command in setOf("ultronComposeAct", "ultronComposeAssert")
        val execute = when (command) {
            "ultronAct", "ultronAssert" -> {
                val assertion = command == "ultronAssert"
                validateFields(p,
                    strings = if (assertion) emptySet() else setOf("action", "text", "name"),
                    objects = if (assertion) setOf("matcher", "condition") else setOf("matcher"),
                    booleans = if (assertion) setOf("exists") else setOf("autoScroll"),
                    integers = if (assertion) setOf("timeoutMs") else setOf("timeoutMs", "offsetX", "offsetY", "keyCode", "metaState", "flags"))
                UltronLlmOperation(assertion).prepare(gson.fromJson(p, UltronOperationParams::class.java))
            }
            "ultronComposeAct", "ultronComposeAssert" -> {
                UltronComposeParams.validateJson(p.toString())
                ComposeOperationBridge.prepare(gson.fromJson(p, UltronComposeParams::class.java), command == "ultronComposeAssert")
            }
            "ultronRecycler" -> {
                validateFields(p, strings = setOf("action", "text", "name"),
                    objects = setOf("matcher", "itemMatcher", "childMatcher", "condition"), booleans = emptySet(),
                    integers = setOf("position", "itemCount", "searchLimit", "timeoutMs"))
                UltronRecycler().prepare(gson.fromJson(p, UltronRecyclerParams::class.java))
            }
            "ultronBack", "ultronHideKeyboard", "ultronElement" -> {
                UltronDeviceParams.validateJson(p.toString())
                UltronDeviceOperation(command).prepare(gson.fromJson(p, UltronDeviceParams::class.java))
            }
            else -> invalid("Unsupported batch command: $command")
        }
        val backend = if (compose || p["backend"]?.asString == "compose") "compose" else "espresso"
        val tree = if (compose) p["useUnmergedTree"]?.asBoolean ?: true else true
        return UltronPreparedOperation(backend, UltronDiagnosticPlan.native(backend, tree), p.has("text"), execute)
    }

    private fun validateFields(p: JsonObject, strings: Set<String>, objects: Set<String>, booleans: Set<String>, integers: Set<String>) {
        for ((key, value) in p.entrySet()) {
            val valid = when (key) {
                in strings -> value.isJsonPrimitive && value.asJsonPrimitive.isString
                in objects -> value.isJsonObject
                in booleans -> value.isJsonPrimitive && value.asJsonPrimitive.isBoolean
                in integers -> value.isJsonPrimitive && value.asJsonPrimitive.isNumber &&
                    runCatching { value.asBigDecimal.intValueExact() }.isSuccess
                else -> false
            }
            if (!valid) invalid("Invalid batch operation parameter: $key")
        }
    }
}

private fun invalid(message: String): Nothing = throw InvalidArgumentException(message)
