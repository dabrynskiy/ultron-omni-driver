package io.appium.espressoserver.test.ultron

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.viewinterop.AndroidView

class ComposeSnapshotActivity : ComponentActivity() {
    lateinit var host: ComposeView
    lateinit var hiddenHost: ComposeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = ComposeView(this).apply {
            setContent { SnapshotContent() }
        }
        hiddenHost = ComposeView(this).apply {
            setContent { Leaf("hidden-leaf") }
        }
        setContentView(FrameLayout(this).apply {
            addView(TextView(context).apply { text = "Native sibling" })
            addView(host, FrameLayout.LayoutParams(400, 300).apply { leftMargin = 35; topMargin = 130 })
            addView(hiddenHost, FrameLayout.LayoutParams(120, 80).apply { topMargin = 500 })
        })
    }

    @Composable
    private fun SnapshotContent() {
        Layout(content = {
            Layout(content = { Leaf("compose-leaf") }, modifier = Modifier.semantics(mergeDescendants = true) {
                testTag = "merge-parent"
            }) { children, constraints ->
                val child = children.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(100, 60) { child.place(5, 7) }
            }
            AndroidView(factory = { TextView(it).apply { text = "Embedded AndroidView" } })
        }) { children, constraints ->
            val placeables = children.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEachIndexed { index, child -> child.place(0, index * 70) }
            }
        }
    }

    @Composable
    private fun Leaf(tag: String) {
        Layout(content = {}, modifier = Modifier.semantics {
            testTag = tag
            text = AnnotatedString("Compose text")
            contentDescription = "Compose description"
            disabled()
            onClick("Activate") { true }
            customActions = listOf(CustomAccessibilityAction("Custom action") { true })
        }) { _, _ -> layout(80, 40) {} }
    }
}
