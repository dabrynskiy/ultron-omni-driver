/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
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

package io.appium.espressoserver.lib.compose

import com.atiurin.ultron.core.compose.nodeinteraction.UltronComposeSemanticsNodeInteraction
import com.atiurin.ultron.core.compose.nodeinteraction.click
import io.appium.espressoserver.lib.handlers.exceptions.InvalidArgumentException
import io.appium.espressoserver.lib.handlers.exceptions.InvalidElementStateException
import io.appium.espressoserver.lib.handlers.exceptions.InvalidSelectorException
import io.appium.espressoserver.lib.handlers.exceptions.NoSuchElementException
import io.appium.espressoserver.lib.handlers.exceptions.StaleElementException
import io.appium.espressoserver.lib.helpers.AndroidLogger
import io.appium.espressoserver.lib.helpers.getNodeInteractionById
import io.appium.espressoserver.lib.helpers.getSemanticsNode
import io.appium.espressoserver.lib.helpers.handleUltronResult
import io.appium.espressoserver.lib.helpers.toNodeInteractionsCollection
import io.appium.espressoserver.lib.model.AppiumParams
import io.appium.espressoserver.lib.model.BaseElement
import io.appium.espressoserver.lib.model.ComposeElement
import io.appium.espressoserver.lib.model.ComposeNodeElement
import io.appium.espressoserver.lib.model.Locator
import io.appium.espressoserver.lib.model.Location
import io.appium.espressoserver.lib.model.MobileSwipeParams
import io.appium.espressoserver.lib.model.Rect
import io.appium.espressoserver.lib.model.Size
import io.appium.espressoserver.lib.model.TextValueParams

internal object ComposeHandlerBridge {
    private fun ultronInteraction(elementId: String?) =
        UltronComposeSemanticsNodeInteraction(
            getNodeInteractionById(elementId),
            resultHandler = ::handleUltronResult,
        )

    fun findElement(params: Locator): BaseElement {
        val nodeInteractions = toNodeInteractionsCollection(params)
        if (nodeInteractions.fetchSemanticsNodes(false).isEmpty()) {
            throw NoSuchElementException(
                String.format(
                    "Could not find a compose element with strategy '%s' and selector '%s'",
                    params.using,
                    params.value,
                ),
            )
        }
        return ComposeElement(nodeInteractions[0])
    }

    fun findElements(params: Locator): List<BaseElement> {
        val nodeInteractions = toNodeInteractionsCollection(params)
        return List(nodeInteractions.fetchSemanticsNodes(false).size) { index ->
            ComposeElement(nodeInteractions[index])
        }
    }

    fun click(params: AppiumParams) {
        try {
            ultronInteraction(params.elementId).click()
        } catch (e: AssertionError) {
            throw StaleElementException(params.elementId!!)
        } catch (e: IllegalArgumentException) {
            throw InvalidElementStateException("Click", params.elementId!!, e)
        }
    }

    fun clear(params: AppiumParams) {
        try {
            ultronInteraction(params.elementId).clearText()
        } catch (e: AssertionError) {
            throw StaleElementException(params.elementId!!)
        } catch (e: IllegalArgumentException) {
            throw InvalidElementStateException("Clear", params.elementId!!, e)
        }
    }

    fun elementValue(params: TextValueParams, isReplacing: Boolean) {
        val value: String = extractTextToEnter(params)
        try {
            val interaction = ultronInteraction(params.elementId)
            if (isReplacing) interaction.replaceText(value) else interaction.inputText(value)
        } catch (e: IllegalArgumentException) {
            throw InvalidElementStateException("Clear", params.elementId!!, e)
        }
    }

    fun keys(params: TextValueParams) {
        try {
            val keys = params.value ?: return
            keys.forEach {
                ultronInteraction(params.elementId).inputText(it)
            }
        } catch (e: AssertionError) {
            throw StaleElementException(params.elementId!!)
        } catch (e: IllegalArgumentException) {
            throw InvalidElementStateException("Keys", params.elementId!!, e)
        }
    }

    fun mobileSwipe(params: MobileSwipeParams): Void? {
        val nodeInteractions = ultronInteraction(params.elementId)
        AndroidLogger.info("Performing swipe action with direction '${params.direction}'")
        when (params.direction) {
            MobileSwipeParams.Direction.UP -> nodeInteractions.swipeUp()
            MobileSwipeParams.Direction.DOWN -> nodeInteractions.swipeDown()
            MobileSwipeParams.Direction.LEFT -> nodeInteractions.swipeLeft()
            MobileSwipeParams.Direction.RIGHT -> nodeInteractions.swipeRight()
            else -> throw InvalidArgumentException(
                "Unknown swipe direction '${params.direction}'. " +
                    "Only the following values are supported: " +
                    MobileSwipeParams.Direction.values().joinToString(",") { x -> x.name.lowercase() },
            )
        }
        return null
    }

    fun getDisplayed(params: AppiumParams): Boolean =
        try {
            ultronInteraction(params.elementId).withTimeout(0).assertIsDisplayed()
            true
        } catch (e: AssertionError) {
            false
        }

    fun elementScreenshot(elementId: String): String =
        ComposeScreenshot.takeComposeNodeScreenshot(getNodeInteractionById(elementId))

    fun getAttribute(elementId: String, attributeName: String): String? =
        ComposeNodeElement(getSemanticsNode(elementId)).getAttribute(attributeName)

    fun getEnabled(params: AppiumParams): Boolean =
        ComposeNodeElement(getSemanticsNode(params.elementId!!)).isEnabled

    fun getLocation(params: AppiumParams): Location {
        val composeNodeElement = ComposeNodeElement(getSemanticsNode(params.elementId!!))
        return Location(composeNodeElement.bounds.left, composeNodeElement.bounds.top)
    }

    fun getName(params: AppiumParams): String? {
        val composeNodeElement = ComposeNodeElement(getSemanticsNode(params.elementId!!))
        return composeNodeElement.contentDescription?.toString()
    }

    fun getRect(params: AppiumParams): Rect =
        ComposeNodeElement(getSemanticsNode(params.elementId!!)).rect

    fun getSelected(params: AppiumParams): Boolean =
        ComposeNodeElement(getSemanticsNode(params.elementId!!)).isSelected

    fun getSize(params: AppiumParams): Size {
        val bounds = ComposeNodeElement(getSemanticsNode(params.elementId!!)).bounds
        return Size(bounds.width(), bounds.height())
    }

    fun text(params: AppiumParams): String =
        ComposeNodeElement(getSemanticsNode(params.elementId!!)).text.toString()

    private fun extractTextToEnter(params: TextValueParams) =
        when (Pair(params.value == null, params.text == null)) {
            Pair(first = true, second = true) ->
                throw InvalidArgumentException("Must provide 'value' or 'text' property")
            Pair(first = false, second = true) -> params.value!!.joinToString(separator = "")
            else -> params.text!!
        }
}
