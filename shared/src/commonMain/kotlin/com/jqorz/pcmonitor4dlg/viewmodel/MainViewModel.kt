package com.jqorz.pcmonitor4dlg.viewmodel

import com.jqorz.pcmonitor4dlg.model.*
import kotlinx.coroutines.flow.StateFlow

interface MainViewModel {
    // System monitoring
    val systemStats: StateFlow<SystemStats>

    // BLE
    val bleConnectionState: StateFlow<BleConnectionState>
    val connectedDeviceName: StateFlow<String>
    val scannedDevices: StateFlow<List<BleDeviceInfo>>
    val bleLogs: StateFlow<List<String>>

    // Settings
    val settings: StateFlow<AppSettings>

    // UI state
    val showSettingsDialog: StateFlow<Boolean>
    val showDevicePicker: StateFlow<Boolean>
    val showExitDialog: StateFlow<Boolean>

    // Actions
    fun startScan()
    fun stopScan()
    fun connectDevice(device: BleDeviceInfo)
    fun disconnectDevice()
    fun clearBleLog()

    fun updateAutoStart(enabled: Boolean)
    fun updateAutoConnectLastDevice(enabled: Boolean)
    fun updateExitAction(action: ExitAction)

    fun setShowSettingsDialog(show: Boolean)
    fun setShowDevicePicker(show: Boolean)
    fun setShowExitDialog(show: Boolean)

    fun handleExitRequest()
    fun performExit()
    fun minimizeToTray()
}

expect fun createMainViewModel(): MainViewModel
