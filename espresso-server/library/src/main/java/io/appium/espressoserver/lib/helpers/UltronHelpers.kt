/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.appium.espressoserver.lib.helpers

import androidx.test.espresso.ViewInteraction
import androidx.test.espresso.web.sugar.Web.WebInteraction
import com.atiurin.ultron.core.common.OperationResult
import com.atiurin.ultron.core.common.UltronOperationType
import com.atiurin.ultron.core.config.UltronConfig
import com.atiurin.ultron.core.espresso.UltronEspressoInteraction
import com.atiurin.ultron.core.espressoweb.webelement.UltronWebDocument
import com.atiurin.ultron.core.uiautomator.UiAutomatorActionType
import com.atiurin.ultron.core.uiautomator.uiobject2.UltronUiObject2
import com.atiurin.ultron.exceptions.UltronOperationException
import java.lang.reflect.InvocationTargetException

/**
 * Use an explicit wrapper: ViewInteraction.perform is a member and shadows Ultron's extension.
 * Keep the original failure types so existing Appium error mapping and fallbacks still work.
 */
internal fun ViewInteraction.ultron(): UltronEspressoInteraction<ViewInteraction> =
    UltronEspressoInteraction(this, resultHandler = ::handleUltronResult)

internal fun handleUltronResult(result: OperationResult<*>) {
    if (!result.success) {
        throw result.exceptions.lastOrNull() ?: UltronOperationException(result.description)
    }
}

/** Global Ultron actions use its default result handler, which wraps the original error. */
internal fun runUltronAction(action: () -> Unit) {
    try {
        action()
    } catch (error: UltronOperationException) {
        throw error.cause ?: error
    }
}

/** Keep native getter values and null for void actions instead of returning an Ultron wrapper. */
internal fun UltronUiObject2.executeForAppium(
    name: String,
    type: UiAutomatorActionType,
    action: () -> Any?,
): Any? {
    var value: Any? = null
    executeAction(
        actionBlock = { value = action() },
        name = name,
        description = name,
        type = type,
        resultHandler = ::handleUltronResult,
    )
    return value
}

/** Run one WebInteraction step without replaying already successful steps or losing nullable values. */
internal fun executeWebForAppium(
    name: String,
    type: UltronOperationType,
    action: () -> WebInteraction<*>,
): WebInteraction<*> {
    lateinit var value: WebInteraction<*>
    UltronWebDocument.executeOperationVoid(
        webInteractionBlock = {
            try {
                value = action()
            } catch (error: InvocationTargetException) {
                // Reflection must not hide the native failure from Ultron's retry policy.
                throw error.cause ?: error
            }
            // The Void entry point does not read get(); keep the actual result for the Appium caller.
            @Suppress("UNCHECKED_CAST")
            (value as WebInteraction<Void>)
        },
        name = name,
        description = name,
        type = type,
        timeoutMs = UltronConfig.Espresso.ACTION_TIMEOUT,
        resultHandler = ::handleUltronResult,
    )
    return value
}
