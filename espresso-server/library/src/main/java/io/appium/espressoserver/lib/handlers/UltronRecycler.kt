package io.appium.espressoserver.lib.handlers

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.PerformException
import androidx.test.espresso.Root
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import com.google.gson.JsonObject
import io.appium.espressoserver.lib.handlers.exceptions.InvalidArgumentException
import io.appium.espressoserver.lib.helpers.ultron
import io.appium.espressoserver.lib.llm.UltronOperationInfo
import io.appium.espressoserver.lib.llm.RecyclerViewProperties
import io.appium.espressoserver.lib.llm.UltronSnapshotResult
import io.appium.espressoserver.lib.llm.UltronSnapshots
import io.appium.espressoserver.lib.model.AppiumParams
import io.appium.espressoserver.lib.model.ScopedMatcher
import io.appium.espressoserver.lib.model.toJsonMatcher
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf

class UltronRecyclerParams : AppiumParams() {
    val matcher: JsonObject? = null
    val action: String? = null
    val position: Int? = null
    val itemMatcher: JsonObject? = null
    val childMatcher: JsonObject? = null
    val condition: JsonObject? = null
    val itemCount: Int? = null
    val searchLimit: Int? = null
    val text: String? = null
    val timeoutMs: Long? = null
    val name: String? = null

    fun validate() {
        if (matcher == null) invalid("matcher is required")
        if (action !in ACTIONS) invalid("action must be one of $ACTIONS")
        if (timeoutMs != null && timeoutMs !in 0..60000) invalid("timeoutMs must be 0..60000")
        if (position != null && position < 0) invalid("position must be non-negative")
        if (searchLimit != null && (itemMatcher == null || searchLimit !in 1..10000)) {
            invalid("searchLimit requires itemMatcher and must be 1..10000")
        }
        if (action == "assertItemCount") {
            if (itemCount == null || itemCount < 0) invalid("non-negative itemCount is required")
            if (position != null || itemMatcher != null) invalid("assertItemCount does not accept an item target")
        } else {
            if ((position != null) == (itemMatcher != null)) invalid("provide exactly one of position or itemMatcher")
            if (itemCount != null) invalid("itemCount requires assertItemCount")
        }
        if (childMatcher != null && action !in TARGET_ACTIONS) invalid("childMatcher requires a target action")
        if ((condition != null) != (action == "assertItem")) invalid("condition is required for assertItem only")
        if (action in TEXT_ACTIONS && text == null) invalid("text is required for $action")
        if (text != null && action !in TEXT_ACTIONS) invalid("text requires typeText or replaceText")
        if (name != null && name.isEmpty()) invalid("name must not be empty")
    }

    private fun invalid(message: String): Nothing = throw InvalidArgumentException(message)

    companion object {
        val TEXT_ACTIONS = setOf("typeText", "replaceText")
        val TARGET_ACTIONS = TEXT_ACTIONS + setOf("click", "longClick", "doubleClick", "clearText", "assertItem")
        val ACTIONS = TARGET_ACTIONS + setOf("scrollToItem", "assertItemExists", "assertItemNotExists", "assertItemCount")
    }
}

/** A single Ultron operation owns selection, bounded adapter search, scrolling and the final action.
 * All View/adapter access stays on Espresso's UI thread, including off-screen holder binding.
 */
class UltronRecycler : RequestHandler<UltronRecyclerParams, UltronSnapshotResult> {
    override fun handleInternal(params: UltronRecyclerParams): UltronSnapshotResult = prepare(params).invoke()

    @Suppress("UNCHECKED_CAST")
    internal fun prepare(params: UltronRecyclerParams): () -> UltronSnapshotResult {
        params.validate()
        if (!RecyclerViewProperties.available) throw InvalidArgumentException("The application does not include AndroidX RecyclerView")
        val query = params.matcher!!.toString().toJsonMatcher().query
        fun itemMatcher(json: JsonObject?): Matcher<View>? = json?.let {
            val parsed = it.toString().toJsonMatcher().query
            if (parsed.scope != null) throw InvalidArgumentException("Use matcher.scope for window selection")
            parsed.matcher as Matcher<View>
        }
        val action = RecyclerOperation(params, itemMatcher(params.itemMatcher), itemMatcher(params.childMatcher), itemMatcher(params.condition))
        return { executePrepared(params, query, action) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun executePrepared(params: UltronRecyclerParams, query: ScopedMatcher, action: RecyclerOperation): UltronSnapshotResult {
        var interaction = onView(query.matcher as Matcher<View>)
        query.scope?.let { interaction = interaction.inRoot(it as Matcher<Root>) }
        var ultron = interaction.ultron()
        params.timeoutMs?.let { ultron = ultron.withTimeout(it) }
        // The list operation performs its own item scrolling. Espresso scrollTo cannot scroll a RecyclerView item.
        ultron.withAutoScroll(false)
        params.name?.let { ultron.withName(it) }
        val listener = UltronSnapshots.listener
        listener.beginCommand()
        val start = android.os.SystemClock.elapsedRealtime()
        fun describe(error: Throwable) = generateSequence(error) { it.cause }.take(10).joinToString("; caused by: ") { it.toString() }
        fun result(status: String, error: Throwable? = null) = UltronSnapshotResult(
            UltronOperationInfo(status, params.name ?: "RecyclerView ${params.action}", "recycler",
                android.os.SystemClock.elapsedRealtime() - start, error?.let(::describe)),
            UltronSnapshots.captureForOperation(),
        ).redactSensitiveDetails(params.text != null)
        return try {
            ultron.perform(action)
            listener.commandResult() ?: result("success")
        } catch (error: Throwable) {
            if (error !is Exception && error !is AssertionError) throw error
            listener.commandResult()?.let {
                it.copy(operation = it.operation.copy(status = "failure", error = describe(error)))
                    .redactSensitiveDetails(params.text != null)
            } ?: result("failure", error)
        } finally { listener.endCommand() }
    }
}

private class RecyclerOperation(
    private val params: UltronRecyclerParams,
    private val itemMatcher: Matcher<View>?,
    private val childMatcher: Matcher<View>?,
    private val condition: Matcher<View>?,
) : ViewAction {
    override fun getDescription() = "RecyclerView ${params.action} ${params.position ?: itemMatcher ?: params.itemCount}"
    override fun getConstraints(): Matcher<View> = if (params.action!!.startsWith("assertItem") && params.action != "assertItem") {
        isAssignableFrom(RecyclerView::class.java)
    } else allOf(isAssignableFrom(RecyclerView::class.java), isDisplayed())

    override fun perform(uiController: UiController, view: View) {
        try {
            val recycler = view as RecyclerView
            val adapter = recycler.adapter
            if (params.action == "assertItemCount") {
                check((adapter?.itemCount ?: 0) == params.itemCount) {
                    "Expected ${params.itemCount} adapter items, found ${adapter?.itemCount ?: 0}"
                }
                return
            }
            val count = adapter?.itemCount ?: 0
            val existsOnly = params.action in setOf("assertItemExists", "assertItemNotExists")
            val positions = if (params.position != null) {
                listOfNotNull(params.position.takeIf { it < count })
            } else findItems(recycler, itemMatcher!!, existsOnly)
            when (params.action) {
                "assertItemExists" -> { check(positions.isNotEmpty()) { "No matching adapter item" }; return }
                "assertItemNotExists" -> { check(positions.isEmpty()) { "Matching adapter item exists at ${positions.first()}" }; return }
            }
            check(positions.size == 1) { "Expected one adapter item, found ${positions.size}${if (positions.size > 1) " or more" else ""}" }
            val position = positions.single()
            checkNotNull(recycler.layoutManager) { "RecyclerView has no LayoutManager" }
            recycler.scrollToPosition(position)
            uiController.loopMainThreadUntilIdle()
            val holder = recycler.findViewHolderForAdapterPosition(position)
            check(holder != null && holder.itemView.parent === recycler && recycler.getChildAdapterPosition(holder.itemView) == position) {
                "Item at adapter position $position is not attached after scrolling"
            }
            val item = holder.itemView
            check(itemMatcher?.matches(item) != false) { "Item changed after scrolling; observe the list again" }
            if (params.action == "scrollToItem") return
            val target = if (childMatcher == null) item else {
                val children = descendants(item).filter { childMatcher.matches(it) }.take(2).toList()
                check(children.size == 1) { "Expected one child inside item $position, found ${children.size}${if (children.size > 1) " or more" else ""}" }
                children.single()
            }
            if (params.action == "assertItem") {
                check(condition!!.matches(target)) { "Item $position does not match $condition" }
                return
            }
            val action = when (params.action) {
                "click" -> ViewActions.click()
                "longClick" -> ViewActions.longClick()
                "doubleClick" -> ViewActions.doubleClick()
                "typeText" -> ViewActions.typeText(params.text!!)
                "replaceText" -> ViewActions.replaceText(params.text!!)
                "clearText" -> ViewActions.clearText()
                else -> error("Unsupported RecyclerView action")
            }
            check(action.constraints.matches(target)) { "Item target does not satisfy ${action.constraints}" }
            action.perform(uiController, target)
        } catch (error: Exception) {
            throw PerformException.Builder().withActionDescription(description)
                .withViewDescription(view.javaClass.name).withCause(error).build()
        }
    }

    /** Like Ultron's adapter search, bind temporary holders for items absent from the View tree.
     * Never claim absence or uniqueness when a suffix of the adapter has not been examined.
     */
    @Suppress("UNCHECKED_CAST")
    private fun findItems(recycler: RecyclerView, matcher: Matcher<View>, existsOnly: Boolean): List<Int> {
        val adapter = recycler.adapter as? RecyclerView.Adapter<RecyclerView.ViewHolder> ?: return emptyList()
        val count = adapter.itemCount
        val limit = minOf(count, params.searchLimit ?: 1000)
        val matches = mutableListOf<Int>()
        for (position in 0 until limit) {
            check(adapter.itemCount == count) { "Adapter changed during search; observe the list again" }
            val attached = recycler.findViewHolderForAdapterPosition(position)?.takeIf {
                recycler.getChildAdapterPosition(it.itemView) == position
            }
            // bindViewHolder retains internal binding-adapter state. A fresh holder avoids stale
            // positions and ConcatAdapter bookkeeping when probing another adapter position.
            val holder = attached ?: adapter.createViewHolder(recycler, adapter.getItemViewType(position))
            try {
                if (attached == null) adapter.bindViewHolder(holder, position)
                if (matcher.matches(holder.itemView)) {
                    matches.add(position)
                    if (existsOnly || matches.size == 2) return matches
                }
            } finally {
                if (attached == null) adapter.onViewRecycled(holder)
            }
        }
        check(limit == count) { "Search limit $limit reached for $count adapter items; cannot establish absence or uniqueness. Increase searchLimit or use position." }
        return matches
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            val child = view.getChildAt(index)
            yield(child)
            yieldAll(descendants(child))
        }
    }
}
