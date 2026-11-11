package io.appium.espressoserver.lib.viewaction

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions
import com.atiurin.ultron.core.config.UltronConfig

/** Keep Espresso's constraints and Back synchronization; route IME-origin keys after the IME stage. */
class PressKeyWithOptions(keyCode: Int, private val metaState: Int?, private val flags: Int?) : ViewAction {
    private val delegate = ViewActions.pressKey(keyCode)

    override fun getConstraints() = delegate.constraints
    override fun getDescription() = "${delegate.description}, metaState=$metaState, flags=$flags"

    override fun perform(uiController: UiController, view: View) {
        val softKeyboard = flags != null && flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0
        val inputConnection = if (softKeyboard) {
            val root = view.rootView.takeIf { it.hasWindowFocus() }
                ?: UltronConfig.Espresso.activeRootLister.listActiveRoots()
                    .singleOrNull { it.decorView.hasWindowFocus() }?.decorView
                ?: error("No focused application window for soft-keyboard input")
            BaseInputConnection(root.findFocus() ?: root, false)
        } else null
        delegate.perform(object : UiController by uiController {
            override fun injectKeyEvent(event: KeyEvent): Boolean {
                val key = KeyEvent(event.downTime, event.eventTime, event.action, event.keyCode, event.repeatCount,
                    metaState ?: event.metaState, event.deviceId, event.scanCode, flags ?: event.flags, event.source)
                if (inputConnection == null) return uiController.injectKeyEvent(key)
                // IME-origin events must enter after the IME stage. Hardware injection can make
                // the IME consume/re-dispatch DOWN after UP has already reached the application.
                // BaseInputConnection returns false even when it queues an event; do not retry it.
                inputConnection.sendKeyEvent(key)
                uiController.loopMainThreadUntilIdle()
                return true
            }
        }, view)
    }
}
