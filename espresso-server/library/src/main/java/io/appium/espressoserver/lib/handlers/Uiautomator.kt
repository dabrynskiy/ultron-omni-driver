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

package io.appium.espressoserver.lib.handlers

import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiObjectNotFoundException
import com.atiurin.ultron.core.uiautomator.UiAutomatorActionType
import com.atiurin.ultron.core.uiautomator.uiobject2.UltronUiObject2
import io.appium.espressoserver.lib.handlers.exceptions.AppiumException
import io.appium.espressoserver.lib.helpers.AndroidLogger
import io.appium.espressoserver.lib.helpers.InteractionHelper.getUiDevice
import io.appium.espressoserver.lib.helpers.executeForAppium
import io.appium.espressoserver.lib.model.UiautomatorParams
import io.appium.espressoserver.lib.model.UiautomatorParams.Action
import io.appium.espressoserver.lib.model.UiautomatorParams.Strategy

class Uiautomator : RequestHandler<UiautomatorParams, List<Any?>> {

    @Throws(AppiumException::class)
    override fun handleInternal(params: UiautomatorParams): List<Any?> {
        val strategy = params.strategy
            ?: throw AppiumException("strategy should be one of '${Strategy.values().map { it.strategyName }}'")
        val action = params.action
            ?: throw AppiumException("action should be one of '${Action.values().map { it.actionName }}'")
        val locator = params.locator ?: throw AppiumException("locator must not be null")
        val index = params.index
        if (index != null && index < 0) {
            throw AppiumException("Index $index must not be negative")
        }

        try {
            val bySelector = selector(strategy, locator)
            val uiObjects = getUiDevice().findObjects(bySelector)
            AndroidLogger.info("Found ${uiObjects.size} UiObject(s)")

            if (index != null && index >= uiObjects.size) {
                throw AppiumException("Index $index is out of bounds for ${uiObjects.size} elements")
            }

            // Preserve the legacy contract: no index means one result/action per initial match.
            val indices = index?.let { listOf(it) } ?: uiObjects.indices.toList()
            return indices.map { targetIndex ->
                UltronUiObject2.by(bySelector).executeForAppium(
                    name = "${action.actionName} on $bySelector at index $targetIndex",
                    type = operationType(action),
                ) {
                    // Re-resolve on each attempt instead of retrying a cached accessibility object.
                    val target = getUiDevice().findObjects(bySelector).getOrNull(targetIndex)
                        ?: throw UiObjectNotFoundException("No element at index $targetIndex for $bySelector")
                    perform(target, action)
                }
            }
        } catch (e: AppiumException) {
            throw e
        } catch (e: Exception) {
            throw AppiumException(e)
        }
    }

    private fun selector(strategy: Strategy, locator: String): BySelector = when (strategy) {
        Strategy.CLASS_NAME -> By.clazz(locator)
        Strategy.ID -> By.res(locator)
        Strategy.TEXT -> By.text(locator)
        Strategy.TEXT_CONTAIN -> By.textContains(locator)
        Strategy.TEXT_ENDS_WITH -> By.textEndsWith(locator)
        Strategy.TEXT_STARTS_WITH -> By.textStartsWith(locator)
        Strategy.DESC -> By.desc(locator)
        Strategy.DESC_CONTAINS -> By.descContains(locator)
        Strategy.DESC_ENDS_WITH -> By.descEndsWith(locator)
        Strategy.DESC_STARTS_WITH -> By.descStartsWith(locator)
        Strategy.APPLICATION_PACKAGE -> By.pkg(locator)
    }

    private fun perform(target: UiObject2, action: Action): Any? = when (action) {
        Action.CLICK -> {
            target.click()
            null
        }
        Action.LONG_CLICK -> {
            target.longClick()
            null
        }
        Action.CLEAR -> {
            target.clear()
            null
        }
        Action.GET_TEXT -> target.text
        Action.GET_CONTENT_DESCRIPTION -> target.contentDescription
        Action.GET_CLASS_NAME -> target.className
        Action.GET_RESOURCE_NAME -> target.resourceName
        Action.GET_VISIBLE_BOUNDS -> target.visibleBounds
        Action.GET_VISIBLE_CENTER -> target.visibleCenter
        Action.GET_APPLICATION_PACKAGE -> target.applicationPackage
        Action.GET_CHILD_COUNT -> target.childCount
        // Ultron's isEnabled/isChecked/... are assertions; Appium requires boolean getters here.
        Action.IS_CHECKABLE -> target.isCheckable
        Action.IS_CHECKED -> target.isChecked
        Action.IS_CLICKABLE -> target.isClickable
        Action.IS_ENABLED -> target.isEnabled
        Action.IS_FOCUSABLE -> target.isFocusable
        Action.IS_FOCUSED -> target.isFocused
        Action.IS_LONG_CLICKABLE -> target.isLongClickable
        Action.IS_SCROLLABLE -> target.isScrollable
        Action.IS_SELECTED -> target.isSelected
    }

    private fun operationType(action: Action): UiAutomatorActionType = when (action) {
        Action.CLICK -> UiAutomatorActionType.CLICK
        Action.LONG_CLICK -> UiAutomatorActionType.LONG_CLICK
        Action.CLEAR -> UiAutomatorActionType.CLEAR_TEXT
        Action.GET_TEXT -> UiAutomatorActionType.GET_TEXT
        Action.GET_CONTENT_DESCRIPTION -> UiAutomatorActionType.GET_CONTENT_DESCRIPTION
        Action.GET_CLASS_NAME -> UiAutomatorActionType.GET_CLASS_NAME
        Action.GET_RESOURCE_NAME -> UiAutomatorActionType.GET_RESOURCE_NAME
        Action.GET_VISIBLE_BOUNDS -> UiAutomatorActionType.GET_VISIBLE_BOUNDS
        Action.GET_VISIBLE_CENTER -> UiAutomatorActionType.GET_VISIBLE_CENTER
        Action.GET_APPLICATION_PACKAGE -> UiAutomatorActionType.GET_APPLICATION_PACKAGE
        Action.GET_CHILD_COUNT -> UiAutomatorActionType.GET_CHILD_COUNT
        else -> UiAutomatorActionType.PERFORM
    }
}
