package io.appium.espressoserver.test.model

import com.google.gson.JsonParseException
import io.appium.espressoserver.lib.handlers.UltronComposeParams
import org.junit.Assert.assertThrows
import org.junit.Test

class UltronComposeParamsTest {
    @Test fun httpParametersRejectGsonCoercionsNullAndUnknownFields() {
        for (body in listOf("[]", "null", """{"action":1}""", """{"text":false}""",
            """{"expected":[1]}""", """{"expected":"Saved"}""", """{"options":null}""",
            """{"useUnmergedTree":"false"}""", """{"timeoutMs":0.5}""", """{"timeoutMs":"0"}""",
            """{"timeoutMs":60001}""", """{"autoScroll":true}""")) {
            assertThrows(body, JsonParseException::class.java) { UltronComposeParams.validateJson(body) }
        }
    }

    @Test fun httpParametersPreserveEmptyArraysTextZeroAndFalse() {
        UltronComposeParams.validateJson("""{"matcher":{"name":"hasTestTag","args":["tag"]},"expected":[],"text":"","timeoutMs":0,"useUnmergedTree":false,"options":{}}""")
    }
}
