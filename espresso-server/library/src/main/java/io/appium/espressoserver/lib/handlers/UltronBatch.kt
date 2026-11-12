package io.appium.espressoserver.lib.handlers

import android.os.SystemClock
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import io.appium.espressoserver.lib.handlers.exceptions.InvalidArgumentException
import io.appium.espressoserver.lib.llm.UltronOperationInfo
import io.appium.espressoserver.lib.llm.UltronSnapshotResult
import io.appium.espressoserver.lib.llm.UltronSnapshots
import io.appium.espressoserver.lib.llm.SnapshotCapture
import io.appium.espressoserver.lib.llm.ViewSnapshot
import io.appium.espressoserver.lib.model.AppiumParams

class UltronBatchParams : AppiumParams() {
    val schemaVersion: Int = 1
    val steps: JsonArray? = null

    companion object {
        fun validateJson(body: String) {
            val root = JsonParser.parseString(body)
            if (!root.isJsonObject || root.asJsonObject.keySet().any { it !in setOf("schemaVersion", "steps") }) {
                invalid("Batch accepts only schemaVersion and steps")
            }
            val version = root.asJsonObject["schemaVersion"]
            if (version != null && (!version.isJsonPrimitive || !version.asJsonPrimitive.isNumber ||
                    runCatching { version.asBigDecimal.intValueExact() }.getOrNull() !in setOf(1, 2))) {
                invalid("schemaVersion must be 1 or 2")
            }
            if (root.asJsonObject["steps"]?.isJsonArray != true) invalid("steps must be an array")
        }
    }
}

data class UltronBatchStepResult(
    val id: String,
    val command: String,
    val status: String,
    val operation: UltronOperationInfo? = null,
    val snapshot: ViewSnapshot? = null,
    val value: Any? = null,
    val element: io.appium.espressoserver.lib.model.BaseElement? = null,
)

data class UltronBatchResult(
    val status: String,
    val steps: List<UltronBatchStepResult>,
    val failedStepId: String?,
    val durationMs: Long,
    val schemaVersion: Int = 1,
)

/** The Router holds its request lock for the entire batch, including failure capture. */
class UltronBatch internal constructor(
    private val captureDiagnostic: (UltronDiagnosticPlan) -> ViewSnapshot = { it.captureSafely() },
    private val capture: (Boolean) -> ViewSnapshot = { SnapshotCapture.capture(useUnmergedTree = it) },
) : RequestHandler<UltronBatchParams, UltronBatchResult> {
    override fun handleInternal(params: UltronBatchParams): UltronBatchResult {
        val steps = prepare(params) // Validate every operation before any UI action.
        val start = SystemClock.elapsedRealtime()
        var failedStepId: String? = null
        val results = steps.map { step ->
            if (failedStepId != null) {
                UltronBatchStepResult(step.id, step.command, "skipped")
            } else {
                val stepStart = SystemClock.elapsedRealtime()
                var result = try {
                    UltronSnapshots.withoutAutomaticCapture(step.operation.execute)
                } catch (error: Throwable) {
                    if (error !is Exception && error !is AssertionError) throw error
                    UltronSnapshotResult(
                        UltronOperationInfo("failure", step.command, "batchStep",
                            SystemClock.elapsedRealtime() - stepStart, error.toString(), step.operation.backend),
                        UltronSnapshots.disabledSnapshot(),
                    )
                }
                val failed = result.operation.status == "failure"
                if (failed) {
                    failedStepId = step.id
                    result = result.copy(snapshot = captureFailure(step.operation.diagnostics, params.schemaVersion))
                }
                result = result.redactSensitiveDetails(step.operation.textInput)
                UltronSnapshots.listener.record(result)
                UltronBatchStepResult(step.id, step.command, result.operation.status, result.operation,
                    if (failed) result.snapshot else null, result.value, result.element)
            }
        }
        return UltronBatchResult(if (failedStepId == null) "success" else "failure", results, failedStepId,
            SystemClock.elapsedRealtime() - start, params.schemaVersion)
    }

    private fun captureFailure(plan: UltronDiagnosticPlan, version: Int): ViewSnapshot = try {
        // Explicit capture ignores the session's automatic snapshot setting.
        if (version == 1) capture(plan.useUnmergedTree) else captureDiagnostic(plan)
    } catch (error: Throwable) {
        if (error !is Exception && error !is AssertionError) throw error
        ViewSnapshot(backend = plan.snapshotBackend, status = "unavailable", roots = emptyList(), nodeCount = 0, truncated = false,
            errors = listOf(error.toString()), capturedAtMs = System.currentTimeMillis(), durationMs = 0)
    }

    private data class PreparedStep(val id: String, val command: String, val operation: UltronPreparedOperation)

    private fun prepare(params: UltronBatchParams): List<PreparedStep> {
        if (params.schemaVersion !in 1..2) invalid("schemaVersion must be 1 or 2")
        val steps = params.steps ?: invalid("steps is required")
        if (steps.size() !in 1..100) invalid("steps must contain 1..100 operations")
        val ids = mutableSetOf<String>()
        return steps.mapIndexed { index, element ->
            if (!element.isJsonObject) invalid("Step $index must be an object")
            val step = element.asJsonObject
            if (step.keySet() != setOf("id", "command", "params")) invalid("Step $index requires only id, command and params")
            fun string(key: String): String = step[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?: invalid("Step $index $key must be a string")
            val id = string("id")
            if (id.isBlank() || id.length > 128 || !ids.add(id)) invalid("Step $index id must be unique and contain 1..128 characters")
            val command = string("command")
            val p = step["params"].takeIf { it.isJsonObject }?.asJsonObject ?: invalid("Step $index params must be an object")
            PreparedStep(id, command, UltronStepRegistry.prepare(command, p, params.schemaVersion))
        }
    }
}

private fun invalid(message: String): Nothing = throw InvalidArgumentException(message)
