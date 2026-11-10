package io.appium.espressoserver.lib.model.settings

import io.appium.espressoserver.lib.handlers.exceptions.InvalidArgumentException
import io.appium.espressoserver.lib.llm.UltronSnapshots

class UltronSnapshotSetting : AbstractSetting() {
    override var name: String = "ultronSnapshotEnabled"

    override fun value(): Boolean = UltronSnapshots.enabled

    override fun apply(value: Any?) {
        if (value !is Boolean) throw InvalidArgumentException("ultronSnapshotEnabled must be a boolean")
        UltronSnapshots.setEnabled(value)
    }
}
