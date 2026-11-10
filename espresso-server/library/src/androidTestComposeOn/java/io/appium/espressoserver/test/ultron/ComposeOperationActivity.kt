package io.appium.espressoserver.test.ultron

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

class ComposeOperationActivity : ComponentActivity() {
    var clicks = 0
    var imeCalls = 0
    var customCalls = 0
    var expandCalls = 0
    var collapseCalls = 0
    var dismissCalls = 0
    var input by mutableStateOf("")
    var progress by mutableFloatStateOf(2f)
    var ready by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column {
                BasicText("Click", Modifier.testTag("button").clickable { clicks++ }.size(120.dp, 40.dp))
                BasicTextField(input, { input = it }, Modifier.testTag("input").size(240.dp, 50.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { imeCalls++ }))
                Row(Modifier.semantics(mergeDescendants = true) { testTag = "merged" }) {
                    BasicText("Alpha", Modifier.testTag("alpha"))
                    BasicText("Beta")
                }
                BasicText("Duplicate", Modifier.testTag("duplicate").clickable { clicks++ })
                BasicText("Duplicate", Modifier.testTag("duplicate").clickable { clicks++ })
                Box(Modifier.size(80.dp, 40.dp).semantics {
                    testTag = "controls"
                    selected = true
                    toggleableState = ToggleableState.Indeterminate
                    stateDescription = "Mixed"
                    contentDescription = "Control description"
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..10f, 9)
                    setProgress { progress = it; true }
                    expand { expandCalls++; true }
                    collapse { collapseCalls++; true }
                    dismiss { dismissCalls++; true }
                    customActions = listOf(CustomAccessibilityAction("Bookmark") { customCalls++; true })
                })
                if (ready) BasicText("Ready", Modifier.testTag("ready"))
                LazyColumn(Modifier.testTag("list").height(160.dp).width(240.dp)) {
                    items(120, key = { "row-$it" }) { index ->
                        BasicText("Row $index", Modifier.testTag("row-$index").height(40.dp))
                    }
                }
            }
        }
    }
}
