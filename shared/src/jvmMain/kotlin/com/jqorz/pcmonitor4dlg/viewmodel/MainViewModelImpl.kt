package com.jqorz.pcmonitor4dlg.viewmodel

import com.jqorz.pcmonitor4dlg.ble.BleManager
import com.jqorz.pcmonitor4dlg.model.*
import com.jqorz.pcmonitor4dlg.monitor.SystemMonitor
import com.jqorz.pcmonitor4dlg.settings.SettingsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest

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

    private var autoConnectJob: Job? = null
    private var pendingConnectAddress: String? = null

    init {
        systemMonitor.start(scope)

        // Auto-start data sync when BLE service becomes ready
        scope.launch {
            bleManager.connectionState.collectLatest { state ->
                if (state == BleConnectionState.SERVICE_READY) {
                    bleManager.startDataSync(scope) { systemMonitor.stats.value }
                }
            }
        }

        // Save last device address on successful connection
        scope.launch {
            bleManager.connectionState.collectLatest { state ->
                if (state == BleConnectionState.SERVICE_READY) {
                    val addr = pendingConnectAddress
                    if (addr != null) {
                        settingsManager.saveLastDeviceAddress(addr)
                        pendingConnectAddress = null
                    }
                }
            }
        }

        // Auto-connect to last device on startup
        startAutoConnectIfNeeded()
    }

    private fun startAutoConnectIfNeeded() {
        autoConnectJob?.cancel()
        if (!settings.value.autoConnectLastDevice) return
        val lastAddr = settingsManager.getLastDeviceAddress() ?: return

        autoConnectJob = scope.launch {
            while (isActive && settings.value.autoConnectLastDevice) {
                if (bleManager.connectionState.value == BleConnectionState.DISCONNECTED) {
                    pendingConnectAddress = lastAddr
                    bleManager.connectByAddress(scope, lastAddr)
                }
                // 等待连接结果或30秒后重试
                val startTime = System.currentTimeMillis()
                while (isActive) {
                    delay(500)
                    val state = bleManager.connectionState.value
                    if (state == BleConnectionState.SERVICE_READY) {
                        // 连接成功，停止自动重连
                        return@launch
                    }
                    if (state == BleConnectionState.DISCONNECTED &&
                        System.currentTimeMillis() - startTime >= 30000
                    ) {
                        // 连接失败，30秒后重试
                        break
                    }
                    if (!settings.value.autoConnectLastDevice) {
                        // 设置已关闭，停止自动重连
                        return@launch
                    }
                }
            }
        }
    }

    override fun startScan() {
        bleManager.startScan(scope)
    }

    override fun stopScan() {
        bleManager.stopScan()
    }

    override fun connectDevice(device: BleDeviceInfo) {
        pendingConnectAddress = device.address
        bleManager.connect(scope, device)
    }

    override fun disconnectDevice() {
        bleManager.disconnect()
    }

    override fun clearBleLog() {
        bleManager.clearLog()
    }

    override fun updateAutoStart(enabled: Boolean) {
        settingsManager.updateAutoStart(enabled)
    }

    override fun updateAutoConnectLastDevice(enabled: Boolean) {
        settingsManager.updateAutoConnectLastDevice(enabled)
        if (enabled) {
            startAutoConnectIfNeeded()
        } else {
            autoConnectJob?.cancel()
            autoConnectJob = null
        }
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
        bleManager.stopBleService()
        scope.cancel()
    }

    override fun minimizeToTray() {
        // 由 UI 层处理窗口隐藏
    }
}

actual fun createMainViewModel(): MainViewModel = MainViewModelImpl()
