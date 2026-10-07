package com.bareminimumstudios.guardian.platform.minecraft

data class CaptureMetrics(
    val submitted: Long,
    val unchanged: Long,
    val backpressure: Long,
    val notRunning: Long,
    val snapshotFailures: Long
)
