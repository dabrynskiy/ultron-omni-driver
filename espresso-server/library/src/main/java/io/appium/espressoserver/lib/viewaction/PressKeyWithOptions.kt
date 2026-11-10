package io.appium.espressoserver.lib.viewaction

import android.view.KeyEvent
import android.view.View
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions

/** Keep Espresso's constraints, injection handling and Back synchronization with JSON key options. */
class PressKeyWithOptions(keyCode: Int, private val metaState: Int?, private val flags: Int?) : ViewAction {
    private val delegate = ViewActions.pressKey(keyCode)

    override fun getConstraints() = delegate.constraints
    override fun getDescription() = "${delegate.description}, metaState=$metaState, flags=$flags"

    override fun perform(uiController: UiController, view: View) {
        delegate.perform(object : UiController by uiController {
            override fun injectKeyEvent(event: KeyEvent): Boolean = uiController.injectKeyEvent(
                KeyEvent(event.downTime, event.eventTime, event.action, event.keyCode, event.repeatCount,
                    metaState ?: event.metaState, event.deviceId, event.scanCode, flags ?: event.flags, event.source),
            )
        }, view)
    }
}
