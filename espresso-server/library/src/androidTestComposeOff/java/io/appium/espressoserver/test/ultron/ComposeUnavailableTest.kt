package io.appium.espressoserver.test.ultron

import com.google.gson.Gson
import io.appium.espressoserver.lib.handlers.UltronComposeOperation
import io.appium.espressoserver.lib.handlers.UltronComposeParams
import io.appium.espressoserver.lib.handlers.exceptions.ComposeNotSupportedException
import org.junit.Assert.assertThrows
import org.junit.Test

class ComposeUnavailableTest {
    @Test fun composeOffRejectsMixedBatchBeforeExecutingAnyNativeStep() {
        val body = """{"steps":[{"id":"native","command":"ultronAssert","params":{"matcher":{"class":"ViewMatchers","name":"isDisplayed"}}},{"id":"compose","command":"ultronComposeAct","params":{"matcher":{"name":"hasTestTag","args":["target"]},"action":"click"}}]}"""
        val params = Gson().fromJson(body, io.appium.espressoserver.lib.handlers.UltronBatchParams::class.java)
        assertThrows(ComposeNotSupportedException::class.java) { io.appium.espressoserver.lib.handlers.UltronBatch().handleInternal(params) }
    }

    @Test fun composeOffRejectsBothExplicitComposeCommandsWithoutLoadingComposeClasses() {
        val params = Gson().fromJson("""{"matcher":{"name":"hasTestTag","args":["target"]},"action":"click"}""", UltronComposeParams::class.java)
        assertThrows(ComposeNotSupportedException::class.java) { UltronComposeOperation(false).handleInternal(params) }
        assertThrows(ComposeNotSupportedException::class.java) { UltronComposeOperation(true).handleInternal(params) }
    }
}
