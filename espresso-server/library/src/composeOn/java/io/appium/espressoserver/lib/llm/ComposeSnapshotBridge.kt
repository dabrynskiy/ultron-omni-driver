package io.appium.espressoserver.lib.llm

import android.annotation.SuppressLint
import android.view.View
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange

internal object ComposeSnapshotBridge {
    @SuppressLint("VisibleForTests")
    fun root(view: View, useUnmergedTree: Boolean): SnapshotNode? {
        if (view !is ViewRootForTest) return null
        val root = if (useUnmergedTree) view.semanticsOwner.unmergedRootSemanticsNode
            else view.semanticsOwner.rootSemanticsNode
        return ComposeNode(root, view, useUnmergedTree, isRoot = true)
    }

    private class ComposeNode(
        private val node: SemanticsNode,
        private val host: View,
        private val useUnmergedTree: Boolean,
        private val isRoot: Boolean = false,
        private val inheritedSensitive: Boolean = false,
    ) : SnapshotNode {
        override fun read(onError: (String, Throwable) -> Unit): Map<String, Any?> {
            val result = linkedMapOf<String, Any?>("type" to "SemanticsNode", "source" to "compose", "node_id" to node.id)
            fun field(name: String, get: () -> Any?) {
                try { result[name] = get() } catch (error: Exception) { onError(name, error) }
            }
            field("bounds") {
                val location = IntArray(2)
                host.getLocationOnScreen(location)
                val bounds = node.boundsInRoot
                linkedMapOf("l" to bounds.left + location[0], "t" to bounds.top + location[1],
                    "r" to bounds.right + location[0], "b" to bounds.bottom + location[1])
                    .also { require(it.values.all { coordinate -> coordinate.isFinite() }) { "Nonfinite semantics bounds" } }
            }
            field("child_count") { node.children.size }
            val hostFields = ViewProperties.read(host, setOf("visibility", "effective_visibility"), onError)
            result.putAll(hostFields)
            if (isRoot) {
                result["semantics_root"] = true
                result["semantics_tree"] = if (useUnmergedTree) "unmerged" else "merged"
                result["compose_view_class"] = host.javaClass.name
                val fields = ViewProperties.read(host, setOf("resourceId", "layoutParams"), onError)
                result["compose_view_resource_id"] = fields["resourceId"]
                result["compose_view_layout_params"] = fields["layoutParams"]
            }
            field("config") {
                val config = node.config
                val sensitive = inheritedSensitive || isSensitive(config)
                result["sensitive"] = sensitive
                if (config.contains(SemanticsProperties.TestTag)) result["test_tag"] = config[SemanticsProperties.TestTag]
                configValues(config, sensitive, onError)
            }
            return result
        }

        override fun children(): Sequence<SnapshotNode> = node.children.asSequence().map {
            ComposeNode(it, host, useUnmergedTree, inheritedSensitive = inheritedSensitive || isSensitive(node.config))
        }
    }

    internal fun isSensitive(config: SemanticsConfiguration): Boolean = config.any { (key, value) ->
        key.name in setOf("Password", "IsSensitiveData") && value != false
    }

    internal fun configValues(
        config: SemanticsConfiguration,
        sensitive: Boolean = isSensitive(config),
        onError: (String, Throwable) -> Unit = { _, error -> throw error },
    ): Map<String, Any?> {
        val values = linkedMapOf<String, Any?>()
        val actions = mutableListOf<String>()
        val units = mutableListOf<String>()
        for ((key, value) in config.toList().sortedBy { it.key.name }) {
            if (key == SemanticsProperties.TestTag) continue
            try {
                when (value) {
                    is AccessibilityAction<*>, is Function<*> -> actions.add(key.name)
                    is Unit -> units.add(key.name)
                    // Redact before conversion: arbitrary objects may disclose values in toString().
                    else -> values[key.name] = if (sensitive) SensitiveData.redact(value) else jsonValue(value)
                }
            } catch (error: Exception) { onError("config.${key.name}", error) }
        }
        if (units.isNotEmpty()) values["units"] = units
        if (actions.isNotEmpty()) values["actions"] = actions
        if (config.isMergingSemanticsOfDescendants) values["merge_descendants"] = true
        if (config.isClearingSemantics) values["clear_and_set_semantics"] = true
        return values
    }

    // Do not expose Compose objects or action lambdas to Gson's reflective serializer.
    internal fun jsonValue(value: Any?): Any? = when (value) {
        null, is String, is Boolean, is Byte, is Short, is Int, is Long -> value
        is Float -> if (value.isFinite()) value else value.toString()
        is Double -> if (value.isFinite()) value else value.toString()
        is AnnotatedString -> value.text
        is TextRange -> mapOf("start" to value.start, "end" to value.end)
        is ProgressBarRangeInfo -> mapOf("current" to jsonValue(value.current), "min" to jsonValue(value.range.start),
            "max" to jsonValue(value.range.endInclusive), "steps" to value.steps,
            "indeterminate" to (value == ProgressBarRangeInfo.Indeterminate))
        is CustomAccessibilityAction -> mapOf("label" to value.label)
        is List<*> -> value.map(::jsonValue)
        else -> value.toString()
    }
}
