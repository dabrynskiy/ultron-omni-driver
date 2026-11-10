package io.appium.espressoserver.lib.llm

import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.text.AnnotatedString
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class SensitiveComposeSnapshotTest {
    @Test fun passwordsRedactAllTextDescriptionsAndCustomSemantics() {
        val secret = "compose-secret-4e2a"
        val config = SemanticsConfiguration().apply {
            this[SemanticsProperties.Password] = Unit
            this[SemanticsProperties.Text] = listOf(AnnotatedString(secret))
            this[SemanticsProperties.EditableText] = AnnotatedString(secret)
            this[SemanticsProperties.ContentDescription] = listOf(secret)
            this[SemanticsPropertyKey<String>("CustomSecret")] = secret
            this[SemanticsPropertyKey<Map<String, String>>("CustomMap")] = mapOf(secret to secret)
        }
        val values = ComposeSnapshotBridge.configValues(config)
        assertTrue(ComposeSnapshotBridge.isSensitive(config))
        assertEquals(listOf("[REDACTED]"), values["Text"])
        assertEquals("[REDACTED]", values["EditableText"])
        assertEquals(listOf("[REDACTED]"), values["ContentDescription"])
        assertEquals("[REDACTED]", values["CustomSecret"])
        assertEquals("[REDACTED]", values["CustomMap"])
        assertFalse(Gson().toJson(values).contains(secret))
    }

    @Test fun sensitiveAncestorAndExplicitSensitiveDataMarkerAlsoRedactValues() {
        val config = SemanticsConfiguration().apply {
            this[SemanticsProperties.Text] = listOf(AnnotatedString("private value"))
        }
        assertEquals(listOf("private value"), ComposeSnapshotBridge.configValues(config)["Text"])
        assertEquals(listOf("[REDACTED]"), ComposeSnapshotBridge.configValues(config, sensitive = true)["Text"])
        config[SemanticsPropertyKey<Boolean>("IsSensitiveData")] = true
        assertEquals(listOf("[REDACTED]"), ComposeSnapshotBridge.configValues(config)["Text"])
    }
}
