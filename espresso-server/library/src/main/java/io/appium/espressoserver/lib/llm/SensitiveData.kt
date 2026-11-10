package io.appium.espressoserver.lib.llm

import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.TextView

/** Snapshot redaction is unconditional: password visibility toggles must not disclose values. */
internal object SensitiveData {
    const val REDACTED = "[REDACTED]"

    fun isPassword(view: TextView): Boolean {
        if (view.transformationMethod is PasswordTransformationMethod) return true
        val type = view.inputType
        val variation = type and InputType.TYPE_MASK_VARIATION
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /** Preserve text-list shape; opaque values and map keys may themselves contain secrets. */
    fun redact(value: Any?): Any? = when (value) {
        null -> null
        is List<*> -> value.map(::redact)
        else -> REDACTED
    }
}
