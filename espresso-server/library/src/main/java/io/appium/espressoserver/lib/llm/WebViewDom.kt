package io.appium.espressoserver.lib.llm

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import com.atiurin.ultron.core.config.UltronConfig
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal data class WebViewTarget(val view: WebView, val node: MutableMap<String, Any?>, val depth: Int)

internal object WebViewDom {
    private class Unavailable(val reason: String) : IllegalStateException(reason)
    private val script: String by lazy {
        InstrumentationRegistry.getInstrumentation().context.assets.open("ultron-webview-snapshot.js")
            .bufferedReader().use { it.readText() }
    }

    /** Only the AUT's in-process WebViews can be read; accessibility nodes are matched conservatively. */
    fun accessibilityTargets(snapshot: ViewSnapshot, deadline: Long): List<WebViewTarget> {
        val nodes = mutableListOf<Pair<MutableMap<String, Any?>, Int>>()
        val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val classes = mutableMapOf<String, Boolean>()
        @Suppress("UNCHECKED_CAST")
        fun visit(node: Map<String, Any?>, depth: Int) {
            val name = node["className"] as? String
            if (node["packageName"] == packageName && name != null && classes.getOrPut(name) {
                name == "android.webkit.WebView" || runCatching {
                    WebView::class.java.isAssignableFrom(Class.forName(name, false, WebViewDom::class.java.classLoader))
                }.getOrDefault(false)
            }) {
                // WebView publishes virtual WebView nodes for documents/frames too. Only the host
                // corresponds to an Android View; frames are traversed by the DOM collector.
                nodes.add(node as MutableMap<String, Any?> to depth)
                return
            }
            (node["children"] as? List<Map<String, Any?>>)?.forEach { visit(it, depth + 1) }
        }
        snapshot.roots.forEach { visit(it, 0) }
        if (nodes.isEmpty()) return emptyList()
        val views = onMain(deadline) {
            val views = mutableListOf<WebView>()
            var visited = 0
            fun find(view: View) {
                if (++visited > 10000 || SystemClock.elapsedRealtime() >= deadline) return
                if (view is WebView) views.add(view)
                else if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))
            }
            UltronConfig.Espresso.activeRootLister.listActiveRoots().forEach { find(it.decorView) }
            views.map { view ->
                val location = IntArray(2).also(view::getLocationOnScreen)
                view to mapOf("l" to location[0], "t" to location[1],
                    "r" to location[0] + view.width, "b" to location[1] + view.height)
            }
        }
        // Update the returned tree only on the caller thread, even when main-thread discovery times out.
        return nodes.mapNotNull { (node, depth) ->
            val matches = views.filter { (_, bounds) -> node["bounds"] == bounds }
            if (matches.size == 1) WebViewTarget(matches.single().first, node, depth) else {
                node["dom_status"] = "unavailable"
                null
            }
        }
    }

    fun enrich(snapshot: ViewSnapshot, targets: List<WebViewTarget>, maxDepth: Int, maxNodes: Int, deadline: Long): ViewSnapshot {
        var count = snapshot.nodeCount
        var truncated = snapshot.truncated
        val errors = snapshot.errors.toMutableList()
        @Suppress("UNCHECKED_CAST")
        fun discoveryErrors(node: Map<String, Any?>) {
            if (node["dom_status"] == "unavailable") errors.add("An in-process WebView could not be identified uniquely")
            (node["children"] as? List<Map<String, Any?>>)?.forEach(::discoveryErrors)
        }
        snapshot.roots.forEach(::discoveryErrors)
        for (target in targets) {
            val node = target.node
            try {
                if (count >= maxNodes || target.depth >= maxDepth) {
                    node["dom_status"] = "truncated"
                    node["truncated"] = true
                    node["truncation_reason"] = if (count >= maxNodes) "maxNodes" else "maxDepth"
                    truncated = true
                    continue
                }
                val result = evaluate(target.view, maxNodes - count, maxDepth - target.depth - 1, deadline)
                val dom = JsonParser.parseString(result).asJsonObject
                @Suppress("UNCHECKED_CAST")
                val roots = Gson().fromJson(dom["roots"], List::class.java) as List<Map<String, Any?>>
                // JSON numbers from Gson are Double; retain the common l/t/r/b shape for CSS-pixel bounds.
                val nativeBounds = node["bounds"] as? Map<*, *>
                val location = nativeBounds?.let { mapOf("l" to it["l"], "t" to it["t"], "r" to it["r"], "b" to it["b"]) }
                roots.forEach { (it as MutableMap<String, Any?>)["webview_bounds"] = location }
                @Suppress("UNCHECKED_CAST")
                val children = (node["children"] as? List<Map<String, Any?>>).orEmpty()
                node["children"] = children + roots
                count += dom["nodeCount"]?.asInt ?: 0
                val domTruncated = dom["truncated"].asBoolean
                truncated = truncated || domTruncated
                val domErrors = dom["errors"].asJsonArray.map { it.asString }
                errors.addAll(domErrors)
                node["dom_status"] = if (domTruncated || domErrors.isNotEmpty()) "partial" else "captured"
            } catch (error: Exception) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                node["dom_status"] = "unavailable"
                // Exceptions from page code must not echo passwords or HTML in snapshots.
                val cause = if (error is ExecutionException) error.cause else error
                val reason = when (cause) {
                    is Unavailable -> cause.reason
                    is TimeoutException -> "timeout"
                    is InterruptedException -> "interrupted"
                    is com.google.gson.JsonParseException, is IllegalStateException -> "invalid_dom_response"
                    else -> "collection_failed"
                }
                node["dom_error"] = reason
                errors.add("WebView DOM unavailable: $reason")
            }
        }
        return snapshot.copy(nodeCount = count, truncated = truncated, errors = errors,
            status = if (snapshot.status == "unavailable") "unavailable" else if (errors.isNotEmpty() || truncated) "partial" else "captured")
    }

    private fun evaluate(view: WebView, maxNodes: Int, maxDepth: Int, deadline: Long): String {
        if (Looper.myLooper() == Looper.getMainLooper()) throw Unavailable("worker_thread_required")
        val future = CompletableFuture<String>()
        val handler = Handler(Looper.getMainLooper())
        val task = Runnable {
            if (future.isCancelled) return@Runnable
            try {
                if (!view.isAttachedToWindow) throw Unavailable("webview_detached")
                if (!view.settings.javaScriptEnabled) throw Unavailable("javascript_disabled")
                view.evaluateJavascript("($script)($maxNodes,$maxDepth)") { future.complete(it) }
            } catch (error: Exception) { future.completeExceptionally(error) }
        }
        if (!handler.post(task)) throw Unavailable("main_thread_unavailable")
        return try { future.get(remaining(deadline), TimeUnit.MILLISECONDS) }
        finally { future.cancel(false); handler.removeCallbacks(task) }
    }

    private fun remaining(deadline: Long): Long = (deadline - SystemClock.elapsedRealtime()).also {
        if (it <= 0) throw TimeoutException("Snapshot deadline exceeded")
    }

    private fun <T> onMain(deadline: Long, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = java.util.concurrent.FutureTask(block)
        val handler = Handler(Looper.getMainLooper())
        check(handler.post(task))
        return try { task.get(remaining(deadline), TimeUnit.MILLISECONDS) }
        finally { task.cancel(false); handler.removeCallbacks(task) }
    }
}
