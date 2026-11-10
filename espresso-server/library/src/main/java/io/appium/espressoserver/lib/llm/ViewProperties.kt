package io.appium.espressoserver.lib.llm

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Checkable
import android.widget.TextView
import androidx.test.espresso.Root
import androidx.test.espresso.matcher.RootMatchers

/** The dump and matchers deliberately share these getters and their value types. */
object ViewProperties {
    val names = setOf(
        "type", "className", "viewId", "resourceId", "contentDescription", "visibility", "effective_visibility", "displayed",
        "alpha", "enabled", "focused", "focusable", "has_focusable", "clickable", "is_selected",
        "layoutParams", "layout_params_class", "tag", "tag_type", "tag_is_stringified",
        "bounds", "bounds.l", "bounds.t", "bounds.r", "bounds.b",
        "visible_bounds", "visible_bounds.l", "visible_bounds.t", "visible_bounds.r", "visible_bounds.b",
        "width", "height", "child_index", "child_count", "text", "transformed_text", "error_text", "hint", "input_type",
        "ime_target", "has_links", "is_checked", "scrollable", "scroll_x", "scroll_y", "sensitive",
        "is_recycler_view", "recycler_item_count", "recycler_layout_manager", "recycler_has_stable_ids",
        "recycler_position", "recycler_binding_position", "recycler_item_view_type", "recycler_item_id",
    )

    fun read(
        view: View,
        properties: Set<String>? = null,
        onError: (String, Throwable) -> Unit = { _, error -> throw error },
    ): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        val sensitive = view is TextView && SensitiveData.isPassword(view)
        fun field(name: String, get: () -> Any?) {
            if (properties != null && name !in properties) return
            if (sensitive && name in setOf("text", "transformed_text", "contentDescription", "tag", "error_text", "hint")) {
                result[name] = SensitiveData.REDACTED
                return
            }
            try { result[name] = get() } catch (error: Exception) { onError(name, error) }
        }
        field("type") { "View" }
        field("sensitive") { sensitive }
        field("className") { view.javaClass.name }
        RecyclerViewProperties.read(view, ::field)
        field("viewId") { view.id }
        field("contentDescription") { view.contentDescription?.toString() }
        field("resourceId") {
            if (view.id == View.NO_ID) "NO_ID"
            else try { view.resources.getResourceName(view.id) }
            catch (_: android.content.res.Resources.NotFoundException) { "0x${Integer.toHexString(view.id)}" }
        }
        field("visibility") { visibility(view.visibility) }
        field("effective_visibility") { effectiveVisibility(view) }
        field("displayed") { isDisplayed(view) }
        field("alpha") { view.alpha.toDouble().also { require(it.isFinite()) { "Nonfinite alpha" } } }
        field("enabled") { view.isEnabled }
        field("focused") { view.isFocused }
        field("focusable") { view.isFocusable }
        field("has_focusable") { view.hasFocusable() }
        field("clickable") { view.isClickable }
        field("is_selected") { view.isSelected }
        field("layoutParams") { view.layoutParams?.javaClass?.simpleName }
        field("layout_params_class") { view.layoutParams?.javaClass?.name }
        field("tag") { tagValue(view.tag) }
        field("tag_type") { view.tag?.javaClass?.name }
        field("tag_is_stringified") { view.tag != null && !isScalar(view.tag) }
        field("bounds") {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            rect(Rect(location[0], location[1], location[0] + view.width, location[1] + view.height))
        }
        field("visible_bounds") {
            val bounds = Rect()
            if (isDisplayed(view) && view.getLocalVisibleRect(bounds)) {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                bounds.offset(location[0], location[1])
                rect(bounds)
            } else null
        }
        field("width") { view.width }
        field("height") { view.height }
        field("child_index") { (view.parent as? ViewGroup)?.indexOfChild(view) ?: -1 }
        field("child_count") { (view as? ViewGroup)?.childCount ?: 0 }
        field("scrollable") {
            view.canScrollVertically(1) || view.canScrollVertically(-1) ||
                view.canScrollHorizontally(1) || view.canScrollHorizontally(-1)
        }
        field("scroll_x") { view.scrollX }
        field("scroll_y") { view.scrollY }
        if (view is Checkable) field("is_checked") { view.isChecked }
        if (view is TextView) {
            field("text") { view.text?.toString() }
            field("transformed_text") { view.transformationMethod?.getTransformation(view.text.toString(), view)?.toString() }
            field("error_text") { view.error?.toString() }
            field("hint") { view.hint?.toString() }
            field("input_type") { view.inputType }
            field("ime_target") { view.isInputMethodTarget }
            field("has_links") { view.urls.isNotEmpty() }
        }
        return result
    }

    private fun isDisplayed(view: View) = effectiveVisibility(view) == "VISIBLE" && view.getGlobalVisibleRect(Rect())

    private fun effectiveVisibility(view: View): String {
        var current: View? = view
        var invisible = false
        while (current != null) {
            if (current.visibility == View.GONE) return "GONE"
            if (current.visibility == View.INVISIBLE) invisible = true
            current = current.parent as? View
        }
        return if (invisible) "INVISIBLE" else "VISIBLE"
    }

    private fun visibility(value: Int) = when (value) {
        View.VISIBLE -> "VISIBLE"
        View.INVISIBLE -> "INVISIBLE"
        View.GONE -> "GONE"
        else -> "UNKNOWN($value)"
    }

    private fun rect(value: Rect) = linkedMapOf("l" to value.left, "t" to value.top, "r" to value.right, "b" to value.bottom)
    private fun isScalar(value: Any?) = value is String || value is Boolean || value is Char ||
        value is Byte || value is Short || value is Int || value is Long ||
        (value is Float && value.isFinite()) || (value is Double && value.isFinite())
    private fun tagValue(value: Any?): Any? = when (value) {
        null, is String, is Boolean, is Int, is Long -> value
        is Byte -> value.toInt()
        is Short -> value.toInt()
        is Float -> value.toDouble().takeIf { it.isFinite() } ?: value.toString()
        is Double -> value.takeIf { it.isFinite() } ?: value.toString()
        else -> value.toString()
    }
}

object WindowProperties {
    val names = setOf("type", "category", "is_dialog", "is_popup", "is_toast", "is_overlay", "root_class")

    fun read(root: Root): Map<String, Any?> {
        val type = root.windowLayoutParams.orNull()?.type
        val rootClass = root.decorView.javaClass.name
        val toast = type == android.view.WindowManager.LayoutParams.TYPE_TOAST
        val popup = type == android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL ||
            type == android.view.WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL ||
            RootMatchers.isPlatformPopup().matches(root)
        val dialog = type != null && !toast && !popup && (
            type == android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG ||
                RootMatchers.isDialog().matches(root))
        val overlay = type != null && type in 1000..2999 && !toast && !popup && !dialog
        return linkedMapOf(
            "type" to type,
            "category" to when { type == null -> "unknown"; toast -> "toast"; popup -> "popup";
                dialog -> "dialog"; overlay -> "overlay"; else -> "application" },
            "is_dialog" to dialog, "is_popup" to popup, "is_toast" to toast, "is_overlay" to overlay,
            "root_class" to rootClass,
        )
    }
}
