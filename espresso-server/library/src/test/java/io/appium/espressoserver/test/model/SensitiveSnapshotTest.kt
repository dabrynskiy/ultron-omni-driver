package io.appium.espressoserver.test.model

import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import io.appium.espressoserver.lib.llm.ViewProperties
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SensitiveSnapshotTest {
    @Test fun passwordVariantsNeverExposeValuesEvenWhenVisibleOrSelectedByProperty() {
        val input = EditText(ApplicationProvider.getApplicationContext())
        val secret = "snapshot-secret-6b8f"
        for (type in listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )) {
            input.inputType = type
            input.setText(secret)
            input.contentDescription = secret
            input.tag = secret
            input.hint = secret
            input.error = secret
            val fields = ViewProperties.read(input)
            assertEquals(true, fields["sensitive"])
            for (name in listOf("text", "transformed_text", "contentDescription", "tag", "hint", "error_text")) {
                assertEquals(name, "[REDACTED]", fields[name])
                assertEquals("[REDACTED]", ViewProperties.read(input, setOf(name))[name])
            }
            assertFalse(Gson().toJson(fields).contains(secret))
        }
    }

    @Test fun customPasswordTransformationIsRecognizedAndOrdinaryTextIsPreserved() {
        val input = EditText(ApplicationProvider.getApplicationContext())
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setText("Ordinary text")
        assertEquals("Ordinary text", ViewProperties.read(input)["text"])
        assertEquals(false, ViewProperties.read(input)["sensitive"])
        input.transformationMethod = PasswordTransformationMethod.getInstance()
        assertEquals("[REDACTED]", ViewProperties.read(input)["text"])
    }
}
