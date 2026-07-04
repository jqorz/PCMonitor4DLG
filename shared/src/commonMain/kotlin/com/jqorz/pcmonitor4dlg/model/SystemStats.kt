package com.jqorz.pcmonitor4dlg.model

data class SystemStats(
    val cpuTemp: Double = 0.0,
    val cpuUsage: Double = 0.0,
    val gpuTemp: Double = 0.0,
    val gpuUsage: Double = 0.0,
    val memUsage: Double = 0.0,
    val netUpSpeed: Long = 0L,
    val netDownSpeed: Long = 0L,
    val timestamp: Long = 0L
) {
    fun formatNetSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1_073_741_824 -> String.format("%.1f GB/s", bytesPerSec / 1_073_741_824.0)
            bytesPerSec >= 1_048_576 -> String.format("%.1f MB/s", bytesPerSec / 1_048_576.0)
            bytesPerSec >= 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }
}
