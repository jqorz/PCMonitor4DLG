package com.jqorz.pcmonitor4dlg.viewmodel

import com.jqorz.pcmonitor4dlg.ble.BleManager
import com.jqorz.pcmonitor4dlg.model.*
import com.jqorz.pcmonitor4dlg.monitor.SystemMonitor
import com.jqorz.pcmonitor4dlg.settings.SettingsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModelImpl : MainViewModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val systemMonitor = SystemMonitor()
    val bleManager = BleManager()
    val settingsManager = SettingsManager()

    override val systemStats: StateFlow<SystemStats> = systemMonitor.stats

    override val bleConnectionState: StateFlow<BleConnectionState> = bleManager.connectionState
    override val connectedDeviceName: StateFlow<String> = bleManager.connectedDeviceName
    override val scannedDevices: StateFlow<List<BleDeviceInfo>> = bleManager.scannedDevices
    override val bleLogs: StateFlow<List<String>> = bleManager.logs

    override val settings: StateFlow<AppSettings> = settingsManager.settings

    private val _showSettingsDialog = MutableStateFlow(false)
    override val showSettingsDialog: StateFlow<Boolean> = _showSettingsDialog.asStateFlow()

    private val _showDevicePicker = MutableStateFlow(false)
    override val showDevicePicker: StateFlow<Boolean> = _showDevicePicker.asStateFlow()

    private val _showExitDialog = MutableStateFlow(false)
    override val showExitDialog: StateFlow<Boolean> = _showExitDialog.asStateFlow()

    init {
        systemMonitor.start(scope)
    }

    override fun startScan() {
        bleManager.startScan(scope)
    }

    override fun stopScan() {
        bleManager.stopScan()
    }

    override fun connectDevice(device: BleDeviceInfo) {
        bleManager.connect(scope, device)
    }

    override fun disconnectDevice() {
        bleManager.disconnect()
    }

    override fun syncTime() {
        bleManager.syncTime(scope)
    }

    override fun clearBleLog() {
        bleManager.clearLog()
    }

    override fun updateAutoStart(enabled: Boolean) {
        settingsManager.updateAutoStart(enabled)
    }

    override fun updateMinimizeOnStartup(enabled: Boolean) {
        settingsManager.updateMinimizeOnStartup(enabled)
    }

    override fun updateExitAction(action: ExitAction) {
        settingsManager.updateExitAction(action)
    }

    override fun setShowSettingsDialog(show: Boolean) {
        _showSettingsDialog.value = show
    }

    override fun setShowDevicePicker(show: Boolean) {
        _showDevicePicker.value = show
    }

    override fun setShowExitDialog(show: Boolean) {
        _showExitDialog.value = show
    }

    override fun handleExitRequest() {
        val action = settings.value.exitAction
        when (action) {
            ExitAction.ASK -> _showExitDialog.value = true
            ExitAction.MINIMIZE -> minimizeToTray()
            ExitAction.EXIT -> performExit()
        }
    }

    override fun performExit() {
        systemMonitor.stop()
        bleManager.disconnect()
        scope.cancel()
    }

    override fun minimizeToTray() {
        // 由 UI 层处理窗口隐藏
    }
}

actual fun createMainViewModel(): MainViewModel = MainViewModelImpl()
