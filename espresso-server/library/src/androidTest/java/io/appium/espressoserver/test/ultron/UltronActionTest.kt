package io.appium.espressoserver.test.ultron

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.PerformException
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.runner.AndroidJUnit4
import com.atiurin.ultron.core.common.Operation
import com.atiurin.ultron.core.common.OperationResult
import com.atiurin.ultron.core.config.UltronCommonConfig
import com.atiurin.ultron.core.config.UltronConfig
import com.atiurin.ultron.core.espresso.action.EspressoActionType
import com.atiurin.ultron.listeners.UltronLifecycleListener
import io.appium.espressoserver.lib.handlers.Clear
import io.appium.espressoserver.lib.handlers.Click
import io.appium.espressoserver.lib.handlers.ElementValue
import io.appium.espressoserver.lib.handlers.exceptions.InvalidElementStateException
import io.appium.espressoserver.lib.helpers.EspressoViewsCache
import io.appium.espressoserver.lib.helpers.ViewState
import io.appium.espressoserver.lib.helpers.ultron
import io.appium.espressoserver.lib.model.AppiumParams
import io.appium.espressoserver.lib.model.EspressoElement
import io.appium.espressoserver.lib.model.TextValueParams
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UltronActionTest {
    private lateinit var scenario: ActivityScenario<UltronActionActivity>
    private lateinit var buttonId: String
    private lateinit var inputId: String
    private var originalTimeout = 0L
    private val results = mutableListOf<OperationResult<Operation>>()
    private val listener = object : UltronLifecycleListener() {
        override fun after(operationResult: OperationResult<Operation>) {
            results.add(operationResult)
        }
    }

    @Before
    fun setUp() {
        originalTimeout = UltronConfig.Espresso.ACTION_TIMEOUT
        UltronConfig.Espresso.ACTION_TIMEOUT = 0
        UltronCommonConfig.addListener(listener)
        scenario = ActivityScenario.launch(UltronActionActivity::class.java)
        scenario.onActivity { activity ->
            buttonId = EspressoElement(ViewState(activity.button)).element
            inputId = EspressoElement(ViewState(activity.input)).element
        }
    }

    @After
    fun tearDown() {
        scenario.close()
        EspressoViewsCache.reset()
        UltronCommonConfig.removeListener(listener.id)
        UltronConfig.Espresso.ACTION_TIMEOUT = originalTimeout
    }

    @Test
    fun appiumHandlersUseUltronAndKeepTheirReturnValues() {
        Click().handleEspresso(AppiumParams().apply { elementId = buttonId })
        ElementValue(true).handleEspresso(TextValueParams(text = "Ultron").apply { elementId = inputId })
        scenario.onActivity { activity ->
            assertEquals(1, activity.clicks)
            assertEquals("Ultron", activity.input.text.toString())
        }
        Clear().handleEspresso(AppiumParams().apply { elementId = inputId })
        scenario.onActivity { activity -> assertEquals("", activity.input.text.toString()) }
        assertEquals(
            listOf(EspressoActionType.CLICK, EspressoActionType.REPLACE_TEXT, EspressoActionType.CLEAR_TEXT),
            results.map { it.operation.type },
        )
        assertTrue(results.all { it.success })
    }

    @Test
    fun customViewActionRetriesInsideOneUltronLifecycle() {
        var attempts = 0
        val action = object : ViewAction {
            override fun getDescription() = "temporarily unavailable action"
            override fun getConstraints() = isAssignableFrom(View::class.java)
            override fun perform(uiController: UiController, view: View) {
                attempts++
                if (attempts == 1) {
                    throw PerformException.Builder()
                        .withActionDescription(description)
                        .withViewDescription("smoke button")
                        .withCause(IllegalStateException("not ready yet"))
                        .build()
                }
                view.performClick()
            }
        }
        onView(withId(UltronActionActivity.BUTTON_ID)).ultron().withTimeout(1000).perform(action)
        assertEquals(2, attempts)
        assertEquals(1, results.size)
        assertTrue(results.single().success)
        scenario.onActivity { activity -> assertEquals(1, activity.clicks) }
    }

    @Test
    fun failedClickKeepsAppiumErrorAndNotifiesListener() {
        scenario.onActivity { activity -> activity.button.translationY = 10000f }
        assertThrows(InvalidElementStateException::class.java) {
            Click().handleEspresso(AppiumParams().apply { elementId = buttonId })
        }
        assertEquals(1, results.size)
        assertFalse(results.single().success)
        scenario.onActivity { activity -> assertEquals(0, activity.clicks) }
    }
}
