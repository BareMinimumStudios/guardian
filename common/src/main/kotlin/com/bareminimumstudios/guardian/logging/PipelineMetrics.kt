package com.bareminimumstudios.guardian.logging

data class PipelineMetrics(
    val accepted: Long,
    val persisted: Long,
    val backpressure: Long,
    val writeFailures: Long,
    val queued: Int,
    val state: PipelineState
)

enum class PipelineState {
    CREATED,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED
}
