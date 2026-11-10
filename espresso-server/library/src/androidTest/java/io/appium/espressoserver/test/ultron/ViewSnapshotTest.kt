package io.appium.espressoserver.test.ultron

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.PerformException
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.runner.AndroidJUnit4
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.appium.espressoserver.lib.handlers.Click
import io.appium.espressoserver.lib.handlers.UltronLlmOperation
import io.appium.espressoserver.lib.handlers.UltronOperationParams
import io.appium.espressoserver.lib.handlers.exceptions.InvalidSelectorException
import io.appium.espressoserver.lib.helpers.EspressoViewsCache
import io.appium.espressoserver.lib.helpers.ViewState
import io.appium.espressoserver.lib.helpers.ultron
import io.appium.espressoserver.lib.llm.*
import io.appium.espressoserver.lib.model.AppiumParams
import io.appium.espressoserver.lib.model.EspressoElement
import io.appium.espressoserver.lib.model.toJsonMatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewSnapshotTest {
    private lateinit var scenario: ActivityScenario<UltronActionActivity>
    private val gson = Gson()

    @Before fun setUp() {
        scenario = ActivityScenario.launch(UltronActionActivity::class.java)
        UltronSnapshots.reset()
        UltronSnapshots.setEnabled(true)
    }

    @After fun tearDown() {
        UltronSnapshots.reset()
        EspressoViewsCache.reset()
        scenario.close()
    }

    @Test fun dumpFieldsRoundTripThroughThePublicJsonMatcher() {
        scenario.onActivity { activity ->
            activity.button.tag = 123
            activity.button.contentDescription = "Submit form"
            activity.button.alpha = .5f
            activity.input.hint = "String"
            activity.input.error = "invalid"
            val views = listOf<View>(activity.button, activity.input, CheckBox(activity).apply { isChecked = true })
            for (view in views) {
                val fields = ViewProperties.read(view)
                for ((name, value) in fields) {
                    if (value is Map<*, *>) {
                        for ((key, coordinate) in value) assertPropertyRoundTrip(view, "$name.$key", coordinate)
                    } else assertPropertyRoundTrip(view, name, value)
                }
            }
            assertEquals(123, ViewProperties.read(activity.button)["tag"])
            assertTrue(jsonMatcher("""{"class":"ViewMatchers","name":"withAlpha","args":[0.5]}""").matches(activity.button))
            assertTrue(jsonMatcher("""{"class":"ViewMatchers","name":"withEffectiveVisibility","args":["VISIBLE"]}""").matches(activity.button))
            assertTrue(jsonMatcher("""{"class":"ViewMatchers","name":"withClassName","args":[{"name":"equalTo","args":["android.widget.Button"]}]}""").matches(activity.button))
            assertTrue(jsonMatcher("""{"name":"instanceOf","args":["android.widget.Button.class"]}""").matches(activity.button))
        }
    }

    @Test fun dumpPreservesHiddenChildrenIndicesAndDifferentFocusProperties() {
        scenario.onActivity { activity ->
            val parent = activity.button.parent as ViewGroup
            val hidden = View(activity).apply { visibility = View.GONE }
            parent.addView(hidden, 0)
            assertEquals(false, ViewProperties.read(parent)["focusable"])
            assertEquals(true, ViewProperties.read(parent)["has_focusable"])
            parent.visibility = View.INVISIBLE
            val childFields = ViewProperties.read(activity.button)
            assertEquals("VISIBLE", childFields["visibility"])
            assertEquals("INVISIBLE", childFields["effective_visibility"])
            assertEquals(false, childFields["displayed"])
            parent.visibility = View.VISIBLE
        }
        val snapshot = ViewHierarchyDumper().capture()
        assertEquals("captured", snapshot.status)
        val nodes = flatten(snapshot.roots)
        assertTrue(nodes.any { it["visibility"] == "GONE" && it["child_index"] == 0 })
        assertEquals(1, nodes.single { it["viewId"] == UltronActionActivity.BUTTON_ID }["child_index"])
        assertTrue(snapshot.nodeCount == nodes.size)
        val limited = ViewHierarchyDumper(maxNodes = 2).capture()
        assertEquals("partial", limited.status)
        assertEquals(2, limited.nodeCount)
        assertTrue(limited.truncated)
        val shallow = ViewHierarchyDumper(maxDepth = 0).capture()
        assertTrue(shallow.truncated)
        assertTrue(shallow.roots.all { it["truncated"] == true })
        assertTrue(Gson().toJson(snapshot).contains("\"roots\":[{"))
    }

    @Test fun textViewPropertiesAndStringifiedObjectTagsUseTheSameSemantics() {
        scenario.onActivity { activity ->
            val text = TextView(activity).apply {
                text = "String"
                hint = "Integer"
                error = "error"
                tag = object { override fun toString() = "object-tag" }
            }
            assertPropertyRoundTrip(text, "error_text", "error")
            assertPropertyRoundTrip(text, "input_type", text.inputType)
            assertPropertyRoundTrip(text, "tag", "object-tag")
            assertPropertyRoundTrip(text, "tag_is_stringified", true)
            assertTrue(jsonMatcher("""{"class":"ViewMatchers","name":"withText","args":["String"]}""").matches(text))
        }
        assertThrows(InvalidSelectorException::class.java) {
            LlmViewMatchers.propertyEquals("made_up", true)
        }
    }

    @Test fun listenerReturnsPostActionSnapshotAndDoesNotChangeTheW3cValue() {
        var element = ""
        scenario.onActivity { element = EspressoElement(ViewState(it.button)).element }
        val value = Click().handleEspresso(AppiumParams().apply { elementId = element })
        assertEquals(Unit, value)
        assertEquals("success", UltronSnapshots.listener.latest!!.operation.status)
        assertEquals("captured", UltronSnapshots.listener.latest!!.snapshot.status)
        scenario.onActivity { assertEquals(1, it.clicks) }
        val act = operation("""{"action":"replaceText","text":"after operation","matcher":{"class":"LlmViewMatchers","name":"propertyEquals","args":["viewId",${UltronActionActivity.INPUT_ID}]},"timeoutMs":0}""")
        val result = UltronLlmOperation(false).handleInternal(act)
        assertEquals("success", result.operation.status)
        assertEquals("after operation", flatten(result.snapshot.roots).single { it["viewId"] == UltronActionActivity.INPUT_ID }["text"])
        val assertion = operation("""{"matcher":{"class":"LlmViewMatchers","name":"propertyEquals","args":["viewId",${UltronActionActivity.INPUT_ID}]},"condition":{"class":"LlmViewMatchers","name":"propertyEquals","args":["text","wrong"]},"timeoutMs":0}""")
        val failed = UltronLlmOperation(true).handleInternal(assertion)
        assertEquals("failure", failed.operation.status)
        assertNotNull(failed.operation.error)
        assertEquals("captured", failed.snapshot.status)
    }

    @Test fun listenerCapturesOnceAfterRetriesAndDumpFailureDoesNotFailTheAction() {
        var captures = 0
        val listener = UltronSnapshotListener { captures++; ViewHierarchyDumper().capture() }
        com.atiurin.ultron.core.config.UltronCommonConfig.addListener(listener)
        var attempts = 0
        try {
            onView(withId(UltronActionActivity.BUTTON_ID)).ultron().withTimeout(1000).perform(object : ViewAction {
                override fun getDescription() = "retry then click"
                override fun getConstraints() = isAssignableFrom(View::class.java)
                override fun perform(uiController: UiController, view: View) {
                    attempts++
                    if (attempts == 1) throw PerformException.Builder().withActionDescription(description)
                        .withViewDescription("button").withCause(IllegalStateException("not ready")).build()
                    view.performClick()
                }
            })
            assertEquals(2, attempts)
            assertEquals(1, captures)
            assertNull(listener.latest!!.operation.error)
        } finally { com.atiurin.ultron.core.config.UltronCommonConfig.removeListener(listener.id) }
        val broken = UltronSnapshotListener { throw IllegalStateException("dump failed") }
        com.atiurin.ultron.core.config.UltronCommonConfig.addListener(broken)
        try {
            onView(withId(UltronActionActivity.BUTTON_ID)).ultron().click()
            assertEquals("success", broken.latest!!.operation.status)
            assertEquals("unavailable", broken.latest!!.snapshot.status)
        } finally { com.atiurin.ultron.core.config.UltronCommonConfig.removeListener(broken.id) }
    }

    @Test fun realDialogWindowCanBeSelectedUsingDumpProperties() {
        lateinit var dialog: AlertDialog
        scenario.onActivity { activity -> dialog = AlertDialog.Builder(activity).setMessage("dialog message").setPositiveButton("OK", null).show() }
        try {
            val snapshot = ViewHierarchyDumper().capture()
            assertEquals("captured", snapshot.status)
            val dialogRoot = snapshot.roots.single { (it["window"] as Map<*, *>)["category"] == "dialog" }
            assertEquals(false, (dialogRoot["window"] as Map<*, *>)["is_overlay"])
            val matcher = """{"class":"ViewMatchers","name":"withText","args":["OK"],"scope":{"class":"LlmRootMatchers","name":"propertyEquals","args":["category","dialog"]}}""".toJsonMatcher().query
            @Suppress("UNCHECKED_CAST")
            onView(matcher.matcher as org.hamcrest.Matcher<View>).inRoot(matcher.scope as org.hamcrest.Matcher<androidx.test.espresso.Root>).ultron().click()
            assertEquals("success", UltronSnapshots.listener.latest!!.operation.status)
        } finally { scenario.onActivity { dialog.dismiss() } }
    }

    private fun assertPropertyRoundTrip(view: View, name: String, value: Any?) {
        val matcher = JsonObject().apply {
            addProperty("class", "LlmViewMatchers")
            addProperty("name", "propertyEquals")
            add("args", gson.toJsonTree(listOf(name, value)))
        }.toString().toJsonMatcher().query.matcher
        assertTrue("$name=$value", matcher.matches(view))
    }
    private fun jsonMatcher(json: String) = json.toJsonMatcher().query.matcher
    private fun operation(json: String) = gson.fromJson(json, UltronOperationParams::class.java)
    @Suppress("UNCHECKED_CAST")
    private fun flatten(roots: List<Map<String, Any?>>): List<Map<String, Any?>> = roots.flatMap { node ->
        listOf(node) + flatten(node["children"] as? List<Map<String, Any?>> ?: emptyList())
    }
}
