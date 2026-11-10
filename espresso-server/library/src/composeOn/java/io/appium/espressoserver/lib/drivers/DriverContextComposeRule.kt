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

package io.appium.espressoserver.lib.drivers

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.atiurin.ultron.core.compose.ComposeTestContainer
import com.atiurin.ultron.core.compose.ComposeTestEnvironment

private val emptyComposeRule: ComposeTestRule = createEmptyComposeRule().also { rule ->
    // Share the server's existing rule; a second Compose rule would create a separate test lifecycle.
    ComposeTestContainer.init(object : ComposeTestEnvironment {
        override val provider get() = rule
        override val density get() = rule.density
        override val mainClock get() = rule.mainClock
    })
}

/**
 * Compose UI test rule for this build. Import [composeTestRule] when using from other packages
 * (`import io.appium.espressoserver.lib.drivers.composeTestRule`).
 */
val DriverContext.composeTestRule: ComposeTestRule
    get() = emptyComposeRule
