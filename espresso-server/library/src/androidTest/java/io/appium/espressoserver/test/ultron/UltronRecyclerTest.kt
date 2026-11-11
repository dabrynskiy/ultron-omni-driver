package io.appium.espressoserver.test.ultron

import android.app.AlertDialog
import android.view.View
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.runner.AndroidJUnit4
import com.google.gson.Gson
import io.appium.espressoserver.lib.handlers.UltronRecycler
import io.appium.espressoserver.lib.handlers.UltronRecyclerParams
import io.appium.espressoserver.lib.handlers.exceptions.InvalidArgumentException
import io.appium.espressoserver.lib.llm.LlmViewMatchers
import io.appium.espressoserver.lib.llm.UltronSnapshotResult
import io.appium.espressoserver.lib.llm.UltronSnapshots
import io.appium.espressoserver.lib.llm.ViewHierarchyDumper
import io.appium.espressoserver.lib.llm.ViewProperties
import io.appium.espressoserver.lib.model.toJsonMatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UltronRecyclerTest {
    private lateinit var scenario: ActivityScenario<UltronRecyclerActivity>
    private val gson = Gson()
    private val list = """{"class":"ViewMatchers","name":"withId","args":[${UltronRecyclerActivity.LIST_ID}]}"""
    private val button = """{"class":"ViewMatchers","name":"withId","args":[${UltronRecyclerActivity.BUTTON_ID}]}"""
    private val input = """{"class":"ViewMatchers","name":"withId","args":[${UltronRecyclerActivity.INPUT_ID}]}"""

    @Before fun before() {
        scenario = ActivityScenario.launch(UltronRecyclerActivity::class.java)
        UltronSnapshots.setEnabled(true)
    }
    @After fun after() {
        UltronSnapshots.reset()
        scenario.close()
    }

    @Test fun batchReusesRecyclerOperationsAndStopsBeforeTheNextItemClick() {
        val body = """{"steps":[
          {"id":"click","command":"ultronRecycler","params":{"matcher":$list,"action":"click","position":0,"childMatcher":$button,"timeoutMs":0}},
          {"id":"count","command":"ultronRecycler","params":{"matcher":$list,"action":"assertItemCount","itemCount":79,"timeoutMs":0}},
          {"id":"never","command":"ultronRecycler","params":{"matcher":$list,"action":"click","position":30,"childMatcher":$button,"timeoutMs":0}}
        ]}"""
        val params = gson.fromJson(body, io.appium.espressoserver.lib.handlers.UltronBatchParams::class.java)
        val result = io.appium.espressoserver.lib.handlers.UltronBatch().handleInternal(params)
        assertEquals(listOf("success", "failure", "skipped"), result.steps.map { it.status })
        assertNull(result.steps[0].snapshot)
        assertTrue(result.steps[1].snapshot!!.nodeCount > 0)
        assertNull(result.steps[2].snapshot)
        scenario.onActivity { assertEquals(0, it.selected); assertEquals(1, it.clicks) }
    }

    @Test fun customRecyclerAndAttachedItemMetadataRoundTripIncludingLongStableIds() {
        success(""""action":"scrollToItem","position":60""")
        val snapshot = ViewHierarchyDumper().capture()
        assertEquals("captured", snapshot.status)
        val nodes = flatten(snapshot.roots)
        val recycler = nodes.single { it["viewId"] == UltronRecyclerActivity.LIST_ID }
        assertEquals(CustomFeedView::class.java.name, recycler["className"])
        assertEquals(true, recycler["is_recycler_view"])
        assertEquals(80, recycler["recycler_item_count"])
        assertEquals(true, recycler["recycler_has_stable_ids"])
        assertTrue((recycler["child_count"] as Int) < 80)
        val row = nodes.single { it["recycler_position"] == 60 }
        assertEquals(60, row["recycler_binding_position"])
        assertEquals("9007199254741053", row["recycler_item_id"])
        scenario.onActivity { activity ->
            val holder = activity.recycler.findViewHolderForAdapterPosition(60)!!
            val properties = ViewProperties.read(holder.itemView)
            for (property in listOf("recycler_position", "recycler_binding_position", "recycler_item_view_type", "recycler_item_id")) {
                assertTrue(LlmViewMatchers.propertyEquals(property, properties[property]).matches(holder.itemView))
            }
            val matcher = """{"class":"ViewMatchers","name":"isAssignableFrom","args":["androidx.recyclerview.widget.RecyclerView"]}""".toJsonMatcher().query.matcher
            assertTrue(matcher.matches(activity.recycler))
        }
    }

    @Test fun offscreenItemAndItsChildAreSelectedWithoutClickingAnotherRepeatedButton() {
        success(""""action":"click","itemMatcher":${item("Item 60")},"childMatcher":$button""")
        scenario.onActivity { assertEquals(60, it.selected); assertEquals(1, it.clicks) }
        success(""""action":"longClick","position":30,"childMatcher":$button""")
        scenario.onActivity { assertEquals(30, it.selected); assertEquals(1, it.longClicks) }
        success(""""action":"replaceText","position":30,"childMatcher":$input,"text":"changed"""")
        success(""""action":"assertItem","position":30,"childMatcher":$input,"condition":${text("changed")}""")
        success(""""action":"assertItemCount","itemCount":80""")
        success(""""action":"assertItemExists","itemMatcher":${item("Item 75")}""")
        success(""""action":"assertItemNotExists","itemMatcher":${item("Missing")}""")
        success(""""action":"assertItemNotExists","position":80""")
        failure(""""action":"assertItemExists","position":80""")
        failure(""""action":"assertItemCount","itemCount":79""")
    }

    @Test fun ambiguityAndIncompleteSearchNeverSelectTheFirstMatchOrClaimAbsence() {
        failure(""""action":"click","itemMatcher":${item("Open")}""")
        failure(""""action":"click","position":0,"childMatcher":{"class":"ViewMatchers","name":"isAssignableFrom","args":["android.widget.TextView"]}""")
        val limited = failure(""""action":"assertItemNotExists","itemMatcher":${item("Item 60")},"searchLimit":10""")
        assertTrue(limited.operation.error!!.contains("Search limit"))
        failure(""""action":"click","itemMatcher":${item("Item 5")},"searchLimit":10""")
        success(""""action":"assertItemExists","itemMatcher":${item("Item 5")},"searchLimit":10""")
        failure(""""action":"click","position":80""")
        scenario.onActivity { assertEquals(0, it.clicks) }
    }

    @Test fun emptyAdapterAndConcatAdapterPositionsAreRepresentedCorrectly() {
        scenario.onActivity { it.recycler.adapter = it.Rows(0) }
        success(""""action":"assertItemCount","itemCount":0""")
        success(""""action":"assertItemNotExists","itemMatcher":${item("Missing")}""")
        scenario.onActivity { it.recycler.adapter = ConcatAdapter(it.Rows(1), it.Rows(80)) }
        success(""""action":"scrollToItem","position":61""")
        val row = flatten(ViewHierarchyDumper().capture().roots).single { it["recycler_position"] == 61 }
        assertEquals(60, row["recycler_binding_position"])
        assertNull(row["recycler_item_id"])
    }

    @Test fun scopeSelectsTheRecyclerInsideADialogEvenWithTheSameResourceId() {
        lateinit var dialog: AlertDialog
        scenario.onActivity { activity ->
            val recycler = CustomFeedView(activity).apply {
                id = UltronRecyclerActivity.LIST_ID
                layoutManager = androidx.recyclerview.widget.LinearLayoutManager(activity)
                adapter = activity.Rows(3)
            }
            dialog = AlertDialog.Builder(activity).setView(recycler).create()
            dialog.show()
        }
        try {
            val scoped = list.dropLast(1) + """, "scope":{"class":"RootMatchers","name":"isDialog"}}"""
            val params = gson.fromJson("""{"matcher":$scoped,"action":"click","position":1,"childMatcher":$button,"timeoutMs":0}""", UltronRecyclerParams::class.java)
            val result = UltronRecycler().handleInternal(params)
            assertEquals(result.toString(), "success", result.operation.status)
            scenario.onActivity { assertEquals(1, it.selected); assertEquals(1, it.clicks) }
        } finally { scenario.onActivity { dialog.dismiss() } }
    }

    @Test fun invalidArgumentsAreRejectedBeforeAnyAction() {
        for (args in listOf(
            """"action":"click"""", """"action":"click","position":-1""",
            """"action":"click","position":0,"itemMatcher":${item("Item 0")}""",
            """"action":"assertItemCount","itemCount":-1""",
            """"action":"click","position":0,"searchLimit":2""",
            """"action":"scrollToItem","position":0,"childMatcher":$button""",
            """"action":"assertItem","position":0""",
            """"action":"replaceText","position":0""",
            """"action":"click","position":0,"itemMatcher":{"name":"anything","scope":{"class":"RootMatchers","name":"isDialog"}}""",
        )) assertThrows(args, InvalidArgumentException::class.java) { execute(args) }
        scenario.onActivity { assertEquals(0, it.clicks) }
    }

    private fun execute(args: String): UltronSnapshotResult = UltronRecycler().handleInternal(
        gson.fromJson("""{"matcher":$list,"timeoutMs":0,$args}""", UltronRecyclerParams::class.java))
    private fun success(args: String): UltronSnapshotResult = execute(args).also {
        assertEquals(it.toString(), "success", it.operation.status)
        assertEquals("captured", it.snapshot.status)
    }
    private fun failure(args: String): UltronSnapshotResult = execute(args).also {
        assertEquals(it.toString(), "failure", it.operation.status)
        assertEquals("captured", it.snapshot.status)
    }
    private fun text(value: String) = """{"class":"ViewMatchers","name":"withText","args":[${gson.toJson(value)}]}"""
    private fun item(value: String) = """{"class":"ViewMatchers","name":"hasDescendant","args":[${text(value)}]}"""
    @Suppress("UNCHECKED_CAST")
    private fun flatten(roots: List<Map<String, Any?>>): List<Map<String, Any?>> = roots.flatMap {
        listOf(it) + flatten(it["children"] as? List<Map<String, Any?>> ?: emptyList())
    }
}
