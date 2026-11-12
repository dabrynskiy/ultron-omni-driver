package io.appium.espressoserver.lib.llm

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import io.appium.espressoserver.lib.helpers.InteractionHelper

/** Reads accessibility directly: no XML round trip, Espresso idle wait or second instrumentation. */
class AccessibilityHierarchyDumper(private val maxDepth: Int = 100, private val maxNodes: Int = 5000) {
    init { require(maxDepth in 0..100 && maxNodes in 1..10000) }

    @Suppress("DEPRECATION")
    fun capture(): ViewSnapshot {
        val start = SystemClock.elapsedRealtime()
        val errors = mutableListOf<String>()
        val roots = mutableListOf<Map<String, Any?>>()
        var count = 0
        var truncated = false
        val deadline = start + 2000

        fun node(info: AccessibilityNodeInfo, depth: Int): MutableMap<String, Any?> {
            count++
            val sensitive = info.isPassword
            val bounds = Rect().also(info::getBoundsInScreen)
            val result = linkedMapOf<String, Any?>(
                "type" to "AccessibilityNode", "source" to "accessibility",
                "className" to info.className?.toString(), "resourceId" to info.viewIdResourceName,
                "packageName" to info.packageName?.toString(), "windowId" to info.windowId,
                "text" to if (sensitive) SensitiveData.REDACTED else info.text?.toString(),
                "contentDescription" to if (sensitive) SensitiveData.REDACTED else info.contentDescription?.toString(),
                "sensitive" to sensitive, "displayed" to info.isVisibleToUser,
                "enabled" to info.isEnabled, "focused" to info.isFocused, "focusable" to info.isFocusable,
                "clickable" to info.isClickable, "long_clickable" to info.isLongClickable,
                "is_selected" to info.isSelected, "checkable" to info.isCheckable,
                "scrollable" to info.isScrollable, "child_count" to info.childCount,
                "bounds" to mapOf("l" to bounds.left, "t" to bounds.top, "r" to bounds.right, "b" to bounds.bottom),
            )
            if (info.isCheckable) result["is_checked"] = info.isChecked
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                result["hint"] = if (sensitive) SensitiveData.REDACTED else info.hintText?.toString()
            }
            val children = mutableListOf<Map<String, Any?>>()
            for (index in 0 until info.childCount) {
                if (depth >= maxDepth || count >= maxNodes || SystemClock.elapsedRealtime() >= deadline) {
                    truncated = true
                    result["truncated"] = true
                    result["truncation_reason"] = when {
                        depth >= maxDepth -> "maxDepth"; count >= maxNodes -> "maxNodes"; else -> "uiTimeout"
                    }
                    break
                }
                val child = info.getChild(index)
                if (child == null) {
                    errors.add("Accessibility child $index is unavailable")
                    continue
                }
                try { children.add(node(child, depth + 1)) } finally { child.recycle() }
            }
            if (children.isNotEmpty()) result["children"] = children
            return result
        }

        try {
            val automation = InteractionHelper.getUiAutomation()
            val original = automation.serviceInfo
            val flags = original.flags
            try {
                original.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                automation.serviceInfo = original
                val windows = automation.windows.sortedByDescending { it.layer }
                try {
                    for (window in windows) {
                        if (count >= maxNodes || SystemClock.elapsedRealtime() >= deadline) { truncated = true; break }
                        val root = window.root ?: continue
                        try {
                            val tree = node(root, 0)
                            tree["window"] = mapOf("id" to window.id, "type" to window.type,
                                "layer" to window.layer, "active" to window.isActive, "focused" to window.isFocused,
                                "source" to "accessibility")
                            roots.add(tree)
                        } finally { root.recycle() }
                    }
                    if (windows.isEmpty()) {
                        val root = automation.rootInActiveWindow
                        if (root != null) try { roots.add(node(root, 0)) } finally { root.recycle() }
                    }
                } finally { windows.forEach { it.recycle() } }
            } finally {
                original.flags = flags
                automation.serviceInfo = original
            }
            if (roots.isEmpty()) errors.add("No accessibility window roots are available")
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            errors.add("Accessibility capture failed: ${error.javaClass.simpleName}")
        }
        return ViewSnapshot(backend = "automator", status = when {
            roots.isEmpty() -> "unavailable"; errors.isNotEmpty() || truncated -> "partial"; else -> "captured"
        }, roots = roots, nodeCount = count, truncated = truncated, errors = errors,
            capturedAtMs = System.currentTimeMillis(), durationMs = SystemClock.elapsedRealtime() - start)
    }
}
