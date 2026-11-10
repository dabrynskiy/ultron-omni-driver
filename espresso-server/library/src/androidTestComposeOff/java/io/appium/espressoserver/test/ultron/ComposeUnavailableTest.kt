package io.appium.espressoserver.test.ultron

import com.google.gson.Gson
import io.appium.espressoserver.lib.handlers.UltronComposeOperation
import io.appium.espressoserver.lib.handlers.UltronComposeParams
import io.appium.espressoserver.lib.handlers.exceptions.ComposeNotSupportedException
import org.junit.Assert.assertThrows
import org.junit.Test

class ComposeUnavailableTest {
    @Test fun composeOffRejectsBothExplicitComposeCommandsWithoutLoadingComposeClasses() {
        val params = Gson().fromJson("""{"matcher":{"name":"hasTestTag","args":["target"]},"action":"click"}""", UltronComposeParams::class.java)
        assertThrows(ComposeNotSupportedException::class.java) { UltronComposeOperation(false).handleInternal(params) }
        assertThrows(ComposeNotSupportedException::class.java) { UltronComposeOperation(true).handleInternal(params) }
    }
}
