package io.appium.espressoserver.test.ultron

import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.runner.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObjectNotFoundException
import com.atiurin.ultron.core.common.Operation
import com.atiurin.ultron.core.common.OperationResult
import com.atiurin.ultron.core.config.UltronCommonConfig
import com.atiurin.ultron.core.config.UltronConfig
import com.atiurin.ultron.core.uiautomator.UiAutomatorActionType
import com.atiurin.ultron.core.uiautomator.uiobject2.UltronUiObject2
import com.atiurin.ultron.listeners.UltronLifecycleListener
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.appium.espressoserver.lib.handlers.Uiautomator
import io.appium.espressoserver.lib.handlers.exceptions.AppiumException
import io.appium.espressoserver.lib.helpers.InteractionHelper.getUiDevice
import io.appium.espressoserver.lib.helpers.executeForAppium
import io.appium.espressoserver.lib.model.UiautomatorParams
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UltronUiAutomatorTest {
    private lateinit var scenario: ActivityScenario<UltronActionActivity>
    private var originalTimeout = 0L
    private val results = mutableListOf<OperationResult<Operation>>()
    private val listener = object : UltronLifecycleListener() {
        override fun after(operationResult: OperationResult<Operation>) {
            results.add(operationResult)
        }
    }

    @Before
    fun setUp() {
        originalTimeout = UltronConfig.UiAutomator.OPERATION_TIMEOUT
        UltronConfig.UiAutomator.OPERATION_TIMEOUT = 1000
        UltronCommonConfig.addListener(listener)
        scenario = ActivityScenario.launch(UltronActionActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
        UltronCommonConfig.removeListener(listener.id)
        UltronConfig.UiAutomator.OPERATION_TIMEOUT = originalTimeout
    }

    @Test
    fun actionsAndGettersUseUltronAndKeepNativeResults() {
        val handler = Uiautomator()
        assertEquals(listOf<Any?>(null), handler.handleInternal(params("text", "Click", "click", 0)))
        scenario.onActivity { activity ->
            assertEquals(1, activity.clicks)
            activity.input.setText("before clear")
        }
        assertEquals(
            listOf<Any?>(null),
            handler.handleInternal(params("clazz", "android.widget.EditText", "clear", 0)),
        )
        scenario.onActivity { activity -> assertEquals("", activity.input.text.toString()) }
        assertEquals(listOf("Click"), handler.handleInternal(params("text", "Click", "getText", 0)))
        assertEquals(
            listOf(UiAutomatorActionType.CLICK, UiAutomatorActionType.CLEAR_TEXT, UiAutomatorActionType.GET_TEXT),
            results.map { it.operation.type },
        )
        assertTrue(results.all { it.success })
    }

    @Test
    fun falseBooleanIsAValueRatherThanAFailedAssertion() {
        scenario.onActivity { activity -> activity.button.isEnabled = false }
        assertEquals(listOf(false), Uiautomator().handleInternal(params("text", "Click", "isEnabled", 0)))
        assertEquals(listOf(true), Uiautomator().handleInternal(params("text", "Click", "isClickable", 0)))
        assertEquals(2, results.size)
        assertTrue(results.all { it.success })
    }

    @Test
    fun indexAndAllMatchesPreserveLegacySelection() {
        var secondClicks = 0
        scenario.onActivity { activity ->
            (activity.button.parent as ViewGroup).addView(Button(activity).apply {
                isAllCaps = false
                text = "Second"
                setOnClickListener { secondClicks++ }
            })
        }
        val handler = Uiautomator()
        assertEquals(
            listOf("Click", "Second"),
            handler.handleInternal(params("clazz", "android.widget.Button", "getText")),
        )
        assertEquals(
            listOf<Any?>(null),
            handler.handleInternal(params("clazz", "android.widget.Button", "click", 1)),
        )
        scenario.onActivity { activity ->
            assertEquals(0, activity.clicks)
            assertEquals(1, secondClicks)
        }
        assertEquals(
            listOf<Any?>(null, null),
            handler.handleInternal(params("clazz", "android.widget.Button", "click")),
        )
        scenario.onActivity { activity ->
            assertEquals(1, activity.clicks)
            assertEquals(2, secondClicks)
        }
    }

    @Test
    fun emptySelectionAndInvalidIndicesDoNotPerformActions() {
        val handler = Uiautomator()
        assertEquals(emptyList<Any?>(), handler.handleInternal(params("text", "missing target", "getText")))
        assertThrows(AppiumException::class.java) {
            handler.handleInternal(params("text", "Click", "click", -1))
        }
        assertThrows(AppiumException::class.java) {
            handler.handleInternal(params("text", "Click", "click", 1))
        }
        assertTrue(results.isEmpty())
        scenario.onActivity { activity -> assertEquals(0, activity.clicks) }
    }

    @Test
    fun adapterRetriesAndNotifiesListenerBeforeRethrowingFailure() {
        var attempts = 0
        val interaction = UltronUiObject2.by(By.text("Click")).withTimeout(1000)
        val value = interaction.executeForAppium("temporarily missing object", UiAutomatorActionType.GET_TEXT) {
            attempts++
            if (attempts == 1) throw UiObjectNotFoundException("not ready yet")
            getUiDevice().findObject(By.text("Click")).text
        }
        assertEquals("Click", value)
        assertEquals(2, attempts)
        assertEquals(1, results.size)
        assertTrue(results.single().success)

        assertThrows(UiObjectNotFoundException::class.java) {
            interaction.withTimeout(0).executeForAppium("missing object", UiAutomatorActionType.GET_TEXT) {
                throw UiObjectNotFoundException("still missing")
            }
        }
        assertEquals(2, results.size)
        assertFalse(results.last().success)
    }

    private fun params(strategy: String, locator: String, action: String, index: Int? = null): UiautomatorParams =
        Gson().fromJson(JsonObject().apply {
            addProperty("strategy", strategy)
            addProperty("locator", locator)
            addProperty("action", action)
            index?.let { addProperty("index", it) }
        }, UiautomatorParams::class.java)
}
