package io.appium.espressoserver.lib.llm

import android.os.SystemClock
import io.appium.espressoserver.lib.handlers.exceptions.InvalidArgumentException

/** Session observation policy; it never changes Espresso/Compose action or locator semantics. */
object SnapshotCapture {
    @Volatile var mode: String = "ultron"
        private set

    fun validateMode(value: Any?): String {
        if (value !in setOf("ultron", "automator")) throw InvalidArgumentException("mode must be ultron or automator")
        return value as String
    }

    fun setMode(value: Any?) { mode = validateMode(value) }

    /** Native context is retained, but only the selected WebView receives a DOM subtree. */
    internal fun captureWeb(matcher: org.hamcrest.Matcher<android.view.View>, frames: com.google.gson.JsonArray): ViewSnapshot {
        val start = SystemClock.elapsedRealtime()
        val targets = mutableListOf<WebViewTarget>()
        var snapshot = ViewHierarchyDumper(maxNodes = 1000,
            onWebView = { view, node, depth ->
                if (matcher.matches(view)) targets.add(WebViewTarget(view, node, depth))
            }).capture()
        val context = mapOf("backend" to "web", "frames" to com.google.gson.Gson().fromJson(frames, List::class.java),
            "webViewMatches" to targets.size)
        if (snapshot.status == "unavailable") return snapshot.copy(context = context)
        if (targets.size != 1) return snapshot.copy(status = "partial", context = context,
            errors = snapshot.errors + "Target WebView unavailable: expected one, found ${targets.size}")
        targets.single().node["diagnostic_target"] = true
        snapshot = WebViewDom.enrich(snapshot, targets, 100, 5000, start + 5000)
        return snapshot.copy(context = context, durationMs = SystemClock.elapsedRealtime() - start)
    }

    fun capture(maxDepth: Int = 100, maxNodes: Int = 5000, useUnmergedTree: Boolean = true,
        mode: String = this.mode): ViewSnapshot {
        validateMode(mode)
        require(maxDepth in 0..100 && maxNodes in 1..10000)
        val start = SystemClock.elapsedRealtime()
        val deadline = start + 5000
        val targets = mutableListOf<WebViewTarget>()
        var snapshot = if (mode == "automator") AccessibilityHierarchyDumper(maxDepth, maxNodes).capture()
        else ViewHierarchyDumper(maxDepth, maxNodes, useUnmergedTree = useUnmergedTree,
            onWebView = { view, node, depth -> targets.add(WebViewTarget(view, node, depth)) }).capture()
        if (snapshot.status == "unavailable") return snapshot.copy(durationMs = SystemClock.elapsedRealtime() - start)
        if (mode == "automator") {
            try { targets.addAll(WebViewDom.accessibilityTargets(snapshot, deadline)) }
            catch (error: Exception) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                snapshot = snapshot.copy(status = "partial", errors = snapshot.errors + "WebView discovery unavailable: ${error.javaClass.simpleName}")
            }
        }
        snapshot = WebViewDom.enrich(snapshot, targets, maxDepth, maxNodes, deadline)
        return snapshot.copy(durationMs = SystemClock.elapsedRealtime() - start)
    }
}
