package com.jqorz.pcmonitor4dlg.viewmodel

import com.jqorz.pcmonitor4dlg.model.*
import kotlinx.coroutines.flow.StateFlow

interface MainViewModel {
    // System monitoring
    val systemStats: StateFlow<SystemStats>

    // BLE
    val bleConnectionState: StateFlow<BleConnectionState>
    val connectedDeviceName: StateFlow<String>
    val connectedDeviceVersion: StateFlow<Int>
    val scannedDevices: StateFlow<List<BleDeviceInfo>>
    val bleLogs: StateFlow<List<String>>

    // Settings
    val settings: StateFlow<AppSettings>

    /** 应用版本号（来自构建脚本，"关于"显示用），读取失败时为 "dev" */
    val appVersion: String

    // UI state
    val showSettingsDialog: StateFlow<Boolean>
    val showDevicePicker: StateFlow<Boolean>
    val showExitDialog: StateFlow<Boolean>
    val showOtaDialog: StateFlow<Boolean>
    val showBluetoothOffDialog: StateFlow<Boolean>

    // OTA firmware upgrade
    val otaInfo: StateFlow<OtaInfo>

    // Actions
    fun startScan()
    fun stopScan()
    fun connectDevice(device: BleDeviceInfo)
    fun disconnectDevice()
    fun clearBleLog()

    fun updateAutoStart(enabled: Boolean)
    fun updateAutoConnectLastDevice(enabled: Boolean)
    fun updateDeviceTheme(dark: Boolean)
    fun updateExitAction(action: ExitAction)

    fun setShowSettingsDialog(show: Boolean)
    fun setShowDevicePicker(show: Boolean)
    fun setShowExitDialog(show: Boolean)
    fun setShowOtaDialog(show: Boolean)
    fun setShowBluetoothOffDialog(show: Boolean)

    fun selectOtaFile()
    fun startOtaUpgrade()

    fun handleExitRequest()
    fun performExit()
    fun minimizeToTray()
}

expect fun createMainViewModel(): MainViewModel
