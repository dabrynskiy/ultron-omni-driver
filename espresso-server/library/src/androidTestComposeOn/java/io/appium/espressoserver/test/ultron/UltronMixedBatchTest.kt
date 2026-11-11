package io.appium.espressoserver.test.ultron

import androidx.test.core.app.ActivityScenario
import com.google.gson.Gson
import io.appium.espressoserver.lib.handlers.UltronBatch
import io.appium.espressoserver.lib.handlers.UltronBatchParams
import io.appium.espressoserver.lib.handlers.exceptions.InvalidArgumentException
import io.appium.espressoserver.lib.http.Server
import io.appium.espressoserver.lib.llm.UltronSnapshots
import org.junit.*
import org.junit.Assert.*

class UltronMixedBatchTest {
    @get:Rule val server = Server()
    private lateinit var scenario: ActivityScenario<BatchOperationActivity>
    private val native = """{"class":"ViewMatchers","name":"withId","args":[${BatchOperationActivity.NATIVE_ID}]}"""
    private val compose = """{"name":"hasTestTag","args":["compose"]}"""

    @Before fun setup() { scenario = ActivityScenario.launch(BatchOperationActivity::class.java) }
    @After fun cleanup() { UltronSnapshots.reset(); scenario.close() }
    private fun run(steps: String) = UltronBatch().handleInternal(Gson().fromJson("""{"steps":[$steps]}""", UltronBatchParams::class.java))
    private fun step(id: String, command: String, p: String) = """{"id":"$id","command":"$command","params":$p}"""

    @Test fun nativeComposeAndNativeAgainObserveEachOthersStateWithoutSnapshots() {
        UltronSnapshots.setEnabled(true)
        val result = run(listOf(
            step("native-click", "ultronAct", """{"matcher":$native,"action":"click","timeoutMs":0}"""),
            step("compose-check", "ultronComposeAssert", """{"matcher":$compose,"assertion":"textEquals","expected":["Compose 1"],"timeoutMs":3000}"""),
            step("compose-click", "ultronComposeAct", """{"matcher":$compose,"action":"click","timeoutMs":0}"""),
            step("native-check", "ultronAssert", """{"matcher":$native,"condition":{"class":"ViewMatchers","name":"withText","args":["Native 1"]},"timeoutMs":3000}"""),
        ).joinToString(","))
        assertEquals(result.toString(), "success", result.status)
        assertEquals(listOf("espresso", "compose", "compose", "espresso"), result.steps.map { it.operation!!.backend })
        assertTrue(result.steps.all { it.snapshot == null })
        scenario.onActivity { assertEquals(1, it.nativeClicks); assertEquals(1, it.composeClicks) }
    }

    @Test fun composeFailureCapturesRequestedTreeAndSkipsNativeMutationWithSnapshotsDisabled() {
        var captures = 0
        val handler = UltronBatch { unmerged ->
            captures++
            assertFalse(unmerged)
            io.appium.espressoserver.lib.llm.ViewHierarchyDumper(useUnmergedTree = unmerged).capture()
        }
        val body = """{"steps":[${step("failure", "ultronComposeAssert", """{"matcher":$compose,"assertion":"textEquals","expected":["Wrong"],"timeoutMs":0,"useUnmergedTree":false}""")},${step("never", "ultronAct", """{"matcher":$native,"action":"click","timeoutMs":0}""")}]}"""
        val result = handler.handleInternal(Gson().fromJson(body, UltronBatchParams::class.java))
        assertEquals(1, captures)
        assertEquals(listOf("failure", "skipped"), result.steps.map { it.status })
        assertTrue(result.steps[0].snapshot!!.nodeCount > 0)
        assertTrue(Gson().toJson(result.steps[0].snapshot).contains("\"semantics_tree\":\"merged\""))
        assertFalse(UltronSnapshots.enabled)
        scenario.onActivity { assertEquals(0, it.nativeClicks) }
    }

    @Test fun invalidLaterComposeOperationIsValidatedBeforeNativeClick() {
        assertThrows(InvalidArgumentException::class.java) {
            run(step("native", "ultronAct", """{"matcher":$native,"action":"click"}""") + "," +
                step("invalid", "ultronComposeAct", """{"matcher":$compose,"action":"scrollToIndex","options":{"index":-1}}"""))
        }
        scenario.onActivity { assertEquals(0, it.nativeClicks) }
    }

    @Test fun explicitElementBackendsResolveFreshLocatorsWithoutSwitchingTheDriver() {
        val strategy = io.appium.espressoserver.lib.drivers.DriverContext.currentStrategyType
        val result = run(listOf(
            step("native", "ultronElement", """{"backend":"espresso","using":"text","value":"Native 0","action":"click"}"""),
            step("compose", "ultronElement", """{"backend":"compose","using":"-android viewtag","value":"compose","action":"click"}"""),
            step("new-native", "ultronElement", """{"backend":"espresso","using":"text","value":"Native 1","action":"text"}"""),
            step("check", "ultronComposeAssert", """{"matcher":$compose,"assertion":"textEquals","expected":["Compose 1"],"timeoutMs":3000}"""),
        ).joinToString(","))
        assertEquals(result.toString(), "success", result.status)
        assertEquals("Native 1", result.steps[2].value)
        assertEquals(strategy, io.appium.espressoserver.lib.drivers.DriverContext.currentStrategyType)
        scenario.onActivity { assertEquals(1, it.nativeClicks); assertEquals(1, it.composeClicks) }
    }
}
