package io.appium.espressoserver

import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import io.appium.espressoserver.lib.handlers.UltronRecycler
import io.appium.espressoserver.lib.handlers.UltronRecyclerParams
import io.appium.espressoserver.lib.handlers.exceptions.InvalidArgumentException
import io.appium.espressoserver.lib.llm.RecyclerViewProperties
import io.appium.espressoserver.lib.llm.ViewProperties
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test

/** Run against the default empty server app to catch accidental runtime RecyclerView dependencies.
 * The library's UI fixtures intentionally include RecyclerView, so they cannot cover this case.
 */
class RecyclerOptionalDependencyTest {
    @Test fun ordinaryViewsStillDumpWhenTheApplicationDoesNotIncludeRecyclerView() {
        val recyclerPresent = try {
            Class.forName("androidx.recyclerview.widget.RecyclerView")
            true
        } catch (_: ClassNotFoundException) { false }
        assumeFalse("This test requires an AUT without RecyclerView", recyclerPresent)
        assertFalse(RecyclerViewProperties.available)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = View(ApplicationProvider.getApplicationContext())
            val properties = ViewProperties.read(view)
            assertEquals(false, properties["is_recycler_view"])
            assertEquals(View::class.java.name, properties["className"])
            assertFalse(properties.containsKey("recycler_item_count"))
        }
        val params = Gson().fromJson(
            """{"matcher":{"name":"anything"},"action":"assertItemCount","itemCount":0}""",
            UltronRecyclerParams::class.java,
        )
        val error = assertThrows(InvalidArgumentException::class.java) { UltronRecycler().handleInternal(params) }
        assertTrue(error.message!!.contains("does not include AndroidX RecyclerView"))
    }
}
