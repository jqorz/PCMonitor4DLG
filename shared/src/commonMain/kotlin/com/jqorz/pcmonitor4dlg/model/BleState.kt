package com.jqorz.pcmonitor4dlg.model

enum class BleConnectionState {
    DISCONNECTED, SCANNING, CONNECTING, CONNECTED, SERVICE_READY
}

data class BleDeviceInfo(
    val name: String,
    val address: String,
    /** 设备广播的固件版本号，0 表示广播中未携带 */
    val version: Int = 0
)
