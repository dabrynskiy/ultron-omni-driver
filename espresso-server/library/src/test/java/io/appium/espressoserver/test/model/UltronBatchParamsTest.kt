package io.appium.espressoserver.test.model

import com.google.gson.Gson
import io.appium.espressoserver.lib.handlers.UltronBatch
import io.appium.espressoserver.lib.handlers.UltronBatchParams
import io.appium.espressoserver.lib.handlers.exceptions.InvalidArgumentException
import io.appium.espressoserver.lib.llm.UltronSnapshots
import org.junit.Assert.*
import org.junit.Test

class UltronBatchParamsTest {
    @Test fun rejectsCoercedVersionsNullAndUnknownEnvelopeFields() {
        for (body in listOf("null", "[]", "{}", """{"steps":null}""", """{"steps":{}}""",
            """{"schemaVersion":"1","steps":[]}""", """{"schemaVersion":true,"steps":[]}""",
            """{"schemaVersion":1.5,"steps":[]}""", """{"schemaVersion":2,"steps":[]}""",
            """{"schemaVersion":null,"steps":[]}""", """{"steps":[],"continueOnFailure":true}""")) {
            assertThrows(body, InvalidArgumentException::class.java) { UltronBatchParams.validateJson(body) }
        }
        UltronBatchParams.validateJson("""{"schemaVersion":1,"steps":[]}""")
    }

    @Test fun rejectsInvalidStepsBeforeTryingToExecuteThem() {
        for (steps in listOf("[]", "[null]", "[{}]",
            """[{"id":"a","command":"ultronSnapshot","params":{}}]""",
            """[{"id":"a","command":"ultronAct","params":{"matcher":{},"timeoutMs":"0"}}]""",
            """[{"id":"a","command":"ultronAssert","params":{"matcher":{},"exists":"false"}}]""",
            """[{"id":"a","command":"ultronAct","params":{"text":1}}]""",
            """[{"id":"a","command":"ultronRecycler","params":{"position":0.5}}]""",
            """[{"id":"a","command":"ultronAssert","params":{"sessionId":"other"}}]""")) {
            val params = Gson().fromJson("""{"steps":$steps}""", UltronBatchParams::class.java)
            assertThrows(steps, InvalidArgumentException::class.java) { UltronBatch().handleInternal(params) }
        }
    }

    @Test fun captureSuppressionIsNestedAndRestoredEvenAfterAnException() {
        assertFalse(UltronSnapshots.automaticCaptureSuppressed)
        assertThrows(IllegalStateException::class.java) {
            UltronSnapshots.withoutAutomaticCapture {
                assertTrue(UltronSnapshots.automaticCaptureSuppressed)
                UltronSnapshots.withoutAutomaticCapture { assertTrue(UltronSnapshots.automaticCaptureSuppressed) }
                assertTrue(UltronSnapshots.automaticCaptureSuppressed)
                error("step failed")
            }
        }
        assertFalse(UltronSnapshots.automaticCaptureSuppressed)
    }
}
