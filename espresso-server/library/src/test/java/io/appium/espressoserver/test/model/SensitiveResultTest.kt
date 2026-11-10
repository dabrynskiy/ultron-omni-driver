package io.appium.espressoserver.test.model

import com.google.gson.Gson
import io.appium.espressoserver.lib.llm.UltronOperationInfo
import io.appium.espressoserver.lib.llm.UltronSnapshotListener
import io.appium.espressoserver.lib.llm.UltronSnapshotResult
import io.appium.espressoserver.lib.llm.ViewSnapshot
import org.junit.Assert.*
import org.junit.Test

class SensitiveResultTest {
    private val secret = "private-input-7f31"

    private fun result(type: String, roots: List<Map<String, Any?>> = emptyList()) = UltronSnapshotResult(
        UltronOperationInfo("failure", "Type '$secret'", type, 0, "Could not enter '$secret'"),
        ViewSnapshot(status = "captured", roots = roots, nodeCount = roots.size, truncated = false,
            errors = emptyList(), capturedAtMs = 0, durationMs = 0),
    )

    @Test fun textOperationNamesAndErrorsCannotEchoInputEvenWithSnapshotsDisabled() {
        for (type in listOf("TYPE_TEXT", "TEXT_INPUT", "REPLACE_TEXT", "SET_TEXT")) {
            val safe = result(type).redactSensitiveDetails()
            assertFalse(Gson().toJson(safe).contains(secret))
            assertEquals(type, safe.operation.name)
            assertEquals("failure", safe.operation.status)
        }
        assertFalse(Gson().toJson(result("act").redactSensitiveDetails(textInput = true)).contains(secret))
    }

    @Test fun sensitiveDescendantsProtectBothReturnedAndSavedOperationDetails() {
        val original = result("CLICK", listOf(mapOf("children" to listOf(mapOf("sensitive" to true)))))
        assertFalse(Gson().toJson(original.redactSensitiveDetails()).contains(secret))
        val listener = UltronSnapshotListener()
        listener.beginCommand()
        listener.record(original)
        assertFalse(Gson().toJson(listener.commandResult()).contains(secret))
        assertFalse(Gson().toJson(listener.latest).contains(secret))
        listener.endCommand()
    }

    @Test fun ordinaryOperationDiagnosticsArePreserved() {
        val original = result("CLICK")
        assertSame(original, original.redactSensitiveDetails())
    }
}
