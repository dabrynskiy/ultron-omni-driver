package io.appium.espressoserver.lib.llm

import android.view.View
import androidx.test.espresso.Root
import io.appium.espressoserver.lib.handlers.exceptions.InvalidSelectorException
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeDiagnosingMatcher

private class PropertyMatcher<T : Any>(
    private val property: String,
    private val expected: Matcher<*>,
    private val read: (T) -> Map<String, Any?>,
) : TypeSafeDiagnosingMatcher<T>() {
    override fun describeTo(description: Description) {
        description.appendText("dump property $property ").appendDescriptionOf(expected)
    }

    override fun matchesSafely(item: T, mismatch: Description): Boolean {
        var value: Any? = read(item)
        for (part in property.split('.')) {
            val map = value as? Map<*, *>
            if (map == null || !map.containsKey(part)) {
                mismatch.appendText("property $property is unavailable")
                return false
            }
            value = map[part]
        }
        if (expected.matches(value)) return true
        mismatch.appendText("$property was ").appendValue(value)
        return false
    }
}

private fun scalarEquals(expected: Any?): Matcher<Any?> = object : org.hamcrest.BaseMatcher<Any?>() {
    override fun matches(actual: Any?): Boolean =
        if (actual is Number && expected is Number) actual.toString().toBigDecimal().compareTo(expected.toString().toBigDecimal()) == 0
        else actual == expected

    override fun describeTo(description: Description) { description.appendValue(expected) }
}

/** All leaf properties in a View dump are available through these static JSON matcher factories. */
object LlmViewMatchers {
    @JvmStatic
    fun withProperty(property: String, expected: Matcher<*>): Matcher<View> {
        if (property !in ViewProperties.names) throw InvalidSelectorException("Unknown View dump property '$property'")
        return PropertyMatcher(property, expected) { view: View ->
            ViewProperties.read(view, setOf(property.substringBefore('.')))
        }
    }

    @JvmStatic
    fun propertyEquals(property: String, expected: Any?): Matcher<View> = withProperty(property, scalarEquals(expected))
}

/** Used in the existing JSON matcher's `scope` field, not as a Matcher<View>. */
object LlmRootMatchers {
    @JvmStatic
    fun withProperty(property: String, expected: Matcher<*>): Matcher<Root> {
        if (property !in WindowProperties.names) throw InvalidSelectorException("Unknown window dump property '$property'")
        return PropertyMatcher(property, expected) { root: Root -> WindowProperties.read(root) }
    }

    @JvmStatic
    fun propertyEquals(property: String, expected: Any?): Matcher<Root> = withProperty(property, scalarEquals(expected))
}
