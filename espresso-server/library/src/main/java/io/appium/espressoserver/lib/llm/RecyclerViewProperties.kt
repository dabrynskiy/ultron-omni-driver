package io.appium.espressoserver.lib.llm

import android.view.View
import androidx.recyclerview.widget.RecyclerView

/** Keep optional RecyclerView bytecode behind a class-availability check for AUTs without it. */
object RecyclerViewProperties {
    private val recyclerClass: Class<*>? = try {
        Class.forName("androidx.recyclerview.widget.RecyclerView", false, RecyclerViewProperties::class.java.classLoader)
    } catch (_: ClassNotFoundException) { null }
    catch (_: LinkageError) { null }

    val available: Boolean get() = recyclerClass != null

    fun read(view: View, field: (String, () -> Any?) -> Unit) {
        val isRecycler = recyclerClass?.isInstance(view) == true
        field("is_recycler_view") { isRecycler }
        if (isRecycler) RecyclerMetadata.list(view, field)
        if (recyclerClass?.isInstance(view.parent) == true) RecyclerMetadata.item(view, field)
    }
}

private object RecyclerMetadata {
    fun list(view: View, field: (String, () -> Any?) -> Unit) {
        val recycler = view as RecyclerView
        field("recycler_item_count") { recycler.adapter?.itemCount }
        field("recycler_layout_manager") { recycler.layoutManager?.javaClass?.name }
        field("recycler_has_stable_ids") { recycler.adapter?.hasStableIds() }
    }

    fun item(view: View, field: (String, () -> Any?) -> Unit) {
        val recycler = view.parent as RecyclerView
        val holder by lazy { recycler.getChildViewHolder(view) }
        field("recycler_position") { recycler.getChildAdapterPosition(view).takeUnless { it == RecyclerView.NO_POSITION } }
        field("recycler_binding_position") {
            @Suppress("DEPRECATION")
            val position = try { holder.bindingAdapterPosition } catch (_: NoSuchMethodError) { holder.adapterPosition }
            position.takeUnless { it == RecyclerView.NO_POSITION }
        }
        field("recycler_item_view_type") { holder.itemViewType }
        // Preserve every Long exactly across JSON and JavaScript.
        field("recycler_item_id") {
            holder.itemId.takeIf { recycler.adapter?.hasStableIds() == true && it != RecyclerView.NO_ID }?.toString()
        }
    }
}
