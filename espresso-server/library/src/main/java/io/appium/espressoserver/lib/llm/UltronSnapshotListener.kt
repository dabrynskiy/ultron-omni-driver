package io.appium.espressoserver.lib.llm

import com.atiurin.ultron.core.common.Operation
import com.atiurin.ultron.core.common.OperationResult
import com.atiurin.ultron.core.config.UltronCommonConfig
import com.atiurin.ultron.core.espresso.UltronEspressoOperation
import com.atiurin.ultron.core.uiautomator.UiAutomatorOperation
import com.atiurin.ultron.core.espressoweb.operation.WebInteractionOperation
import com.atiurin.ultron.listeners.UltronLifecycleListener
import io.appium.espressoserver.lib.compose.ComposeOperationBridge

data class UltronOperationInfo(
    val status: String,
    val name: String,
    val type: String,
    val durationMs: Long,
    val error: String? = null,
    val backend: String = "espresso",
)

data class UltronSnapshotResult(val operation: UltronOperationInfo, val snapshot: ViewSnapshot,
    val value: Any? = null, val element: io.appium.espressoserver.lib.model.BaseElement? = null) {
    /** Ultron operation names and exception messages can echo typed text or a View's contents. */
    fun redactSensitiveDetails(textInput: Boolean = false): UltronSnapshotResult {
        fun sensitive(nodes: List<*>): Boolean = nodes.any { value ->
            val node = value as? Map<*, *> ?: return@any false
            node["sensitive"] == true || sensitive(node["children"] as? List<*> ?: emptyList<Any>())
        }
        if (!textInput && operation.type !in setOf("TYPE_TEXT", "TEXT_INPUT", "REPLACE_TEXT", "SET_TEXT") &&
            !sensitive(snapshot.roots)) return this
        return copy(operation = operation.copy(name = operation.type,
            error = operation.error?.let { "Operation failed; sensitive details redacted" }))
    }
}

/** Captures after the final retry, on success and failure. It never executes another Ultron operation. */
class UltronSnapshotListener(private val capture: (() -> ViewSnapshot)? = null) : UltronLifecycleListener() {
    private val depth = ThreadLocal.withInitial { 0 }
    private val current = ThreadLocal<UltronSnapshotResult?>()
    private val commandActive = ThreadLocal.withInitial { false }
    private val tree = ThreadLocal.withInitial { true }
    val useUnmergedTree: Boolean get() = tree.get() ?: true
    @Volatile var latest: UltronSnapshotResult? = null
        private set

    override fun before(operation: Operation) {
        if (supported(operation)) depth.set((depth.get() ?: 0) + 1)
    }

    override fun after(operationResult: OperationResult<Operation>) {
        if (!supported(operationResult.operation)) return
        depth.set(((depth.get() ?: 0) - 1).coerceAtLeast(0))
        if (depth.get() != 0) return
        // A dump failure must never replace the action's native result or exception.
        val snapshot = if (UltronSnapshots.automaticCaptureSuppressed) UltronSnapshots.disabledSnapshot() else try {
            if (ComposeOperationBridge.isOperation(operationResult.operation)) ComposeOperationBridge.waitForIdle()
            capture?.invoke() ?: SnapshotCapture.capture(useUnmergedTree = useUnmergedTree)
        } catch (error: Throwable) {
            if (error !is Exception && error !is AssertionError) throw error
            ViewSnapshot(status = "unavailable", roots = emptyList(), nodeCount = 0, truncated = false,
                errors = listOf(error.toString()), capturedAtMs = System.currentTimeMillis(), durationMs = 0)
        }
        val result = UltronSnapshotResult(
            UltronOperationInfo(
                status = if (operationResult.success) "success" else "failure",
                name = operationResult.operation.name,
                type = operationResult.operation.type.toString(),
                durationMs = operationResult.executionTimeMs,
                error = if (operationResult.success) null else operationResult.exceptions.lastOrNull()?.toString(),
                backend = when (operationResult.operation) {
                    is UiAutomatorOperation -> "automator"
                    is WebInteractionOperation<*> -> "web"
                    is UltronEspressoOperation -> "espresso"
                    else -> "compose"
                },
            ), snapshot,
        )
        depth.remove()
        record(result)
    }

    private fun supported(operation: Operation) = operation is UltronEspressoOperation ||
        operation is UiAutomatorOperation || operation is WebInteractionOperation<*> || ComposeOperationBridge.isOperation(operation)
    fun record(result: UltronSnapshotResult) {
        val safe = result.redactSensitiveDetails()
        if (commandActive.get() == true) current.set(safe)
        latest = safe
    }
    fun beginCommand(useUnmergedTree: Boolean = true) { current.remove(); depth.remove(); tree.set(useUnmergedTree); commandActive.set(true) }
    fun commandResult(): UltronSnapshotResult? = current.get()
    fun endCommand() { current.remove(); depth.remove(); tree.remove(); commandActive.remove() }
    fun reset() { endCommand(); latest = null }
}

object UltronSnapshots {
    val listener = UltronSnapshotListener()
    private val suppressionDepth = ThreadLocal.withInitial { 0 }
    internal val automaticCaptureSuppressed: Boolean get() = suppressionDepth.get() > 0
    @Volatile private var captureEnabled = false
    val enabled: Boolean get() = captureEnabled

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (value) {
            if (!captureEnabled) UltronCommonConfig.addListener(listener)
        } else {
            UltronCommonConfig.removeListener(listener.id)
            listener.reset()
        }
        captureEnabled = value
    }

    fun reset() { setEnabled(false); SnapshotCapture.setMode("ultron") }

    /** Batch steps keep operation results but defer capture until the first failed step. */
    internal fun <T> withoutAutomaticCapture(block: () -> T): T {
        val previous = suppressionDepth.get()
        suppressionDepth.set(previous + 1)
        return try { block() } finally {
            if (previous == 0) suppressionDepth.remove() else suppressionDepth.set(previous)
        }
    }

    internal fun disabledSnapshot() = ViewSnapshot(
        backend = if (SnapshotCapture.mode == "automator") "automator" else "espresso",
        status = "disabled", roots = emptyList(), nodeCount = 0, truncated = false,
        errors = emptyList(), capturedAtMs = System.currentTimeMillis(), durationMs = 0,
    )

    /** Explicit ultronSnapshot remains available; this gate controls automatic post-operation dumps. */
    fun captureForOperation(): ViewSnapshot = if (enabled && !automaticCaptureSuppressed)
        SnapshotCapture.capture(useUnmergedTree = listener.useUnmergedTree) else disabledSnapshot()
}
