package io.appium.espressoserver.lib.llm

/** Flavor-independent traversal so Views and semantics share limits and error handling. */
internal interface SnapshotNode {
    fun read(onError: (String, Throwable) -> Unit): Map<String, Any?>
    fun children(): Sequence<SnapshotNode>
}
