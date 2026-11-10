package io.appium.espressoserver.lib.compose

import io.appium.espressoserver.lib.handlers.UltronComposeParams
import io.appium.espressoserver.lib.handlers.exceptions.ComposeNotSupportedException
import io.appium.espressoserver.lib.llm.UltronSnapshotResult

internal object ComposeOperationBridge {
    fun isOperation(operation: com.atiurin.ultron.core.common.Operation): Boolean = false
    fun waitForIdle() = Unit
    fun execute(params: UltronComposeParams, assertion: Boolean): UltronSnapshotResult = throw ComposeNotSupportedException()
}
