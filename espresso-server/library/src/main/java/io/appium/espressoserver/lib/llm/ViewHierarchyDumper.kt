package io.appium.espressoserver.lib.llm

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.espresso.Root
import com.atiurin.ultron.core.config.UltronConfig
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

data class ViewSnapshot(
    val snapshotId: String = UUID.randomUUID().toString(),
    val backend: String = "espresso",
    val schemaVersion: Int = 1,
    val status: String,
    val roots: List<Map<String, Any?>>,
    val nodeCount: Int,
    val truncated: Boolean,
    val errors: List<String>,
    val capturedAtMs: Long,
    val durationMs: Long,
)

/** Compose access lives in the flavor bridge; all nodes share one traversal budget. */
class ViewHierarchyDumper(
    private val maxDepth: Int = 100,
    private val maxNodes: Int = 5000,
    private val uiTimeoutMs: Long = 2000,
    private val roots: () -> List<Root> = { UltronConfig.Espresso.activeRootLister.listActiveRoots() },
    private val useUnmergedTree: Boolean = true,
) {
    init { require(maxDepth in 0..100 && maxNodes in 1..10000 && uiTimeoutMs > 0) }

    fun capture(): ViewSnapshot {
        val start = android.os.SystemClock.elapsedRealtime()
        return try {
            onMainThread {
                val state = State(start + uiTimeoutMs)
                val trees = mutableListOf<Map<String, Any?>>()
                for (root in roots()) {
                    try {
                        // Match the visible-root policy of LlmReadables; hidden children are retained.
                        if (root.decorView.visibility != View.VISIBLE) continue
                        if (state.count >= maxNodes || state.expired()) { state.truncated = true; break }
                        val tree = state.node(ViewNode(root.decorView), 0)
                        try {
                            tree["window"] = WindowProperties.read(root)
                            if (root.windowLayoutParams.orNull() == null) state.errors.add("Window layout params are unavailable")
                        } catch (error: Exception) { state.errors.add("window: $error") }
                        trees.add(tree)
                    } catch (error: Exception) {
                        state.errors.add("root: $error")
                        trees.add(mapOf("type" to "Error", "error" to error.toString()))
                    }
                }
                ViewSnapshot(
                    status = if (state.errors.isEmpty() && !state.truncated) "captured" else "partial",
                    roots = trees, nodeCount = state.count, truncated = state.truncated, errors = state.errors,
                    capturedAtMs = System.currentTimeMillis(), durationMs = android.os.SystemClock.elapsedRealtime() - start,
                )
            }
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            ViewSnapshot(status = "unavailable", roots = emptyList(), nodeCount = 0, truncated = false,
                errors = listOf(error.toString()), capturedAtMs = System.currentTimeMillis(),
                durationMs = android.os.SystemClock.elapsedRealtime() - start)
        }
    }

    private inner class State(private val deadline: Long) {
        var count = 0
        var truncated = false
        val errors = mutableListOf<String>()
        fun expired() = android.os.SystemClock.elapsedRealtime() >= deadline

        fun node(node: SnapshotNode, depth: Int): MutableMap<String, Any?> {
            count++
            val fieldErrors = mutableListOf<String>()
            val result = node.read { name, error -> fieldErrors.add("$name: $error") }.toMutableMap()
            if (fieldErrors.isNotEmpty()) {
                result["errors"] = fieldErrors
                errors.addAll(fieldErrors)
            }
            try {
                val children = mutableListOf<Map<String, Any?>>()
                result["children"] = children
                val iterator = node.children().iterator()
                var index = 0
                while (iterator.hasNext()) {
                    if (depth >= maxDepth || count >= maxNodes || expired()) {
                        result["truncated"] = true
                        result["truncation_reason"] = when { depth >= maxDepth -> "maxDepth"; count >= maxNodes -> "maxNodes"; else -> "uiTimeout" }
                        truncated = true
                        break
                    }
                    try { children.add(node(iterator.next(), depth + 1)) }
                    catch (error: Exception) {
                        errors.add("child $index: $error")
                        children.add(mapOf("type" to "Error", "child_index" to index, "error" to error.toString()))
                    }
                    index++
                }
                if (children.isEmpty()) result.remove("children")
            } catch (error: Exception) {
                val message = "children: $error"
                errors.add(message)
                result["errors"] = fieldErrors + message
            }
            return result
        }
    }

    private inner class ViewNode(private val view: View) : SnapshotNode {
        override fun read(onError: (String, Throwable) -> Unit) = ViewProperties.read(view, onError = onError)

        override fun children(): Sequence<SnapshotNode> = sequence {
            // Keep the host and native AndroidView descendants, adding semantics as a typed subtree.
            val semantics = try { ComposeSnapshotBridge.root(view, useUnmergedTree) }
            catch (error: Exception) {
                object : SnapshotNode {
                    override fun read(onError: (String, Throwable) -> Unit): Map<String, Any?> {
                        onError("compose", error)
                        return mapOf("type" to "Error", "error" to error.toString())
                    }
                    override fun children() = emptySequence<SnapshotNode>()
                }
            }
            semantics?.let { yield(it) }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) yield(ViewNode(view.getChildAt(index)))
            }
        }
    }

    private fun <T> onMainThread(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(block)
        val handler = Handler(Looper.getMainLooper())
        check(handler.post(task)) { "Could not schedule View dump on UI thread" }
        return try { task.get(uiTimeoutMs, TimeUnit.MILLISECONDS) }
        finally { handler.removeCallbacks(task); task.cancel(false) }
    }
}
