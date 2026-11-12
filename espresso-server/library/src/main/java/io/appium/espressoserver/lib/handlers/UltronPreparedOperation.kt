package io.appium.espressoserver.lib.handlers

import android.os.SystemClock
import io.appium.espressoserver.lib.llm.*

/** Prepared without touching the UI. Runtime diagnostic context belongs to this invocation only. */
internal data class UltronPreparedOperation(
    val backend: String,
    val diagnostics: UltronDiagnosticPlan,
    val textInput: Boolean = false,
    val execute: () -> UltronSnapshotResult,
)

internal class UltronDiagnosticPlan(
    val useUnmergedTree: Boolean = true,
    val snapshotBackend: String = "espresso",
    private val capture: () -> ViewSnapshot,
) {
    fun captureSafely(): ViewSnapshot = try { capture() } catch (error: Throwable) {
        if (error !is Exception && error !is AssertionError) throw error
        ViewSnapshot(backend = snapshotBackend, status = "unavailable", roots = emptyList(), nodeCount = 0, truncated = false,
            errors = listOf("Diagnostic capture failed: ${error.javaClass.simpleName}"),
            capturedAtMs = System.currentTimeMillis(), durationMs = 0)
    }

    companion object {
        fun native(backend: String, useUnmergedTree: Boolean = true) = UltronDiagnosticPlan(useUnmergedTree, if (backend == "automator") "automator" else "espresso") {
            SnapshotCapture.capture(useUnmergedTree = useUnmergedTree,
                mode = if (backend == "automator") "automator" else "ultron")
        }
    }
}

/** The same operation boundary is used by individual commands and batch steps. */
internal fun observedUltronOperation(
    backend: String, type: String, diagnostics: UltronDiagnosticPlan, textInput: Boolean,
    block: () -> Any?,
): UltronSnapshotResult {
    val start = SystemClock.elapsedRealtime()
    var error: Throwable? = null
    val value = try { UltronSnapshots.withoutAutomaticCapture(block) } catch (failure: Throwable) {
        if (failure !is Exception && failure !is AssertionError) throw failure
        error = failure
        null
    }
    val duration = SystemClock.elapsedRealtime() - start
    val snapshot = if (UltronSnapshots.enabled && !UltronSnapshots.automaticCaptureSuppressed)
        diagnostics.captureSafely() else UltronSnapshots.disabledSnapshot()
    return UltronSnapshotResult(
        UltronOperationInfo(if (error == null) "success" else "failure", type, type, duration,
            error?.toString(), backend), snapshot, value,
    ).redactSensitiveDetails(textInput).also(UltronSnapshots.listener::record)
}
