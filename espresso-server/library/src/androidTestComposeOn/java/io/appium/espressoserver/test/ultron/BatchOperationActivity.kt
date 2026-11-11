package io.appium.espressoserver.test.ultron

import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class BatchOperationActivity : ComponentActivity() {
    var nativeClicks by mutableIntStateOf(0)
    var composeClicks by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column {
                AndroidView(factory = { context -> Button(context).apply {
                    id = NATIVE_ID
                    setOnClickListener { nativeClicks++ }
                } }, update = { it.text = "Native $composeClicks" })
                BasicText("Compose $nativeClicks", Modifier.testTag("compose").size(160.dp, 50.dp).clickable { composeClicks++ })
            }
        }
    }

    companion object { const val NATIVE_ID = 0x0100ff31 }
}
