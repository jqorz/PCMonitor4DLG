package com.jqorz.pcmonitor4dlg.model

enum class BleConnectionState {
    DISCONNECTED, SCANNING, CONNECTING, CONNECTED, SERVICE_READY
}

data class BleDeviceInfo(
    val name: String,
    val address: String
)
