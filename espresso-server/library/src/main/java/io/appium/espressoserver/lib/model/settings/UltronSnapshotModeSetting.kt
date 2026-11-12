package io.appium.espressoserver.lib.model.settings

import io.appium.espressoserver.lib.llm.SnapshotCapture

class UltronSnapshotModeSetting : AbstractSetting() {
    override var name: String = "ultronSnapshotMode"
    override fun value(): String = SnapshotCapture.mode
    override fun apply(value: Any?) { SnapshotCapture.setMode(value) }
}
