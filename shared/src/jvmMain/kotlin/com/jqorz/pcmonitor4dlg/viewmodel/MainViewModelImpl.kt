package com.jqorz.pcmonitor4dlg.viewmodel

import com.jqorz.pcmonitor4dlg.ble.BleManager
import com.jqorz.pcmonitor4dlg.ble.BluetoothRadio
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
    override val connectedDeviceVersion: StateFlow<Int> = bleManager.connectedDeviceVersion
    override val scannedDevices: StateFlow<List<BleDeviceInfo>> = bleManager.scannedDevices
    override val bleLogs: StateFlow<List<String>> = bleManager.logs

    override val settings: StateFlow<AppSettings> = settingsManager.settings

    private val _showSettingsDialog = MutableStateFlow(false)
    override val showSettingsDialog: StateFlow<Boolean> = _showSettingsDialog.asStateFlow()

    private val _showDevicePicker = MutableStateFlow(false)
    override val showDevicePicker: StateFlow<Boolean> = _showDevicePicker.asStateFlow()

    private val _showExitDialog = MutableStateFlow(false)
    override val showExitDialog: StateFlow<Boolean> = _showExitDialog.asStateFlow()

    private val _showOtaDialog = MutableStateFlow(false)
    override val showOtaDialog: StateFlow<Boolean> = _showOtaDialog.asStateFlow()

    private val _showBluetoothOffDialog = MutableStateFlow(false)
    override val showBluetoothOffDialog: StateFlow<Boolean> = _showBluetoothOffDialog.asStateFlow()

    private val _otaInfo = MutableStateFlow(OtaInfo())
    override val otaInfo: StateFlow<OtaInfo> = _otaInfo.asStateFlow()

    private var autoConnectJob: Job? = null
    private var pendingConnectAddress: String? = null
    private var pendingConnectVersion: Int? = null

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

        // Save last device address/version on successful connection
        scope.launch {
            bleManager.connectionState.collectLatest { state ->
                if (state == BleConnectionState.SERVICE_READY) {
                    val addr = pendingConnectAddress
                    val ver = pendingConnectVersion
                    if (addr != null) {
                        settingsManager.saveLastDeviceAddress(addr)
                        if (ver != null && ver > 0) settingsManager.saveLastDeviceVersion(ver)
                        pendingConnectAddress = null
                        pendingConnectVersion = null
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
                if (bleManager.connectionState.value == BleConnectionState.DISCONNECTED &&
                    !bleManager.isOtaActive // OTA期间设备会主动断开重启，不要抢着重连
                ) {
                    pendingConnectAddress = lastAddr
                    // 版本号每次从设置读取，OTA 升级成功后无需重启应用即可显示新版本
                    bleManager.connectByAddress(scope, lastAddr, settingsManager.getLastDeviceVersion())
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
        pendingConnectVersion = device.version.takeIf { it > 0 }
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
        // 门禁：打开设备选择器前先检查蓝牙开关，未开启则直接提示，不再继续
        if (show && !BluetoothRadio.isBluetoothEnabled()) {
            _showBluetoothOffDialog.value = true
            return
        }
        _showDevicePicker.value = show
    }

    override fun setShowExitDialog(show: Boolean) {
        _showExitDialog.value = show
    }

    override fun setShowOtaDialog(show: Boolean) {
        _showOtaDialog.value = show
    }

    override fun setShowBluetoothOffDialog(show: Boolean) {
        _showBluetoothOffDialog.value = show
    }

    /** 弹出系统文件选择框（默认桌面目录），选中后立即校验固件有效性 */
    override fun selectOtaFile() {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "选择固件文件", java.awt.FileDialog.LOAD)
        dialog.file = "*.bin"
        // 默认目录：桌面（打包脚本会把 tft_monitor_ota_*.bin 拷到桌面）
        val desktop = sequenceOf(
            java.io.File(System.getProperty("user.home"), "Desktop"),
            java.io.File("D:\\桌面")
        ).firstOrNull { it.isDirectory }
        if (desktop != null) dialog.directory = desktop.absolutePath
        dialog.isVisible = true
        val fileName = dialog.file ?: return
        val filePath = java.io.File(dialog.directory, fileName).absolutePath

        scope.launch(Dispatchers.IO) {
            _otaInfo.value = OtaInfo(fileName = fileName, filePath = filePath)
            try {
                val version = bleManager.validateFirmware(filePath)
                val size = java.io.File(filePath).length().toInt()
                _otaInfo.value = OtaInfo(
                    fileName = fileName, filePath = filePath,
                    fileSize = size, fileVersion = version
                )
            } catch (e: Exception) {
                _otaInfo.value = OtaInfo(
                    fileName = fileName, filePath = filePath,
                    fileError = e.message ?: "文件读取失败"
                )
            }
        }
    }

    /** 开始OTA升级：结果经 [OtaInfo.running]/[OtaInfo.progress]/[OtaInfo.resultMessage] 推送到UI */
    override fun startOtaUpgrade() {
        val info = _otaInfo.value
        if (!info.fileValid || info.running) return
        if (bleManager.connectionState.value != BleConnectionState.SERVICE_READY) {
            _otaInfo.value = info.copy(resultMessage = "设备未连接，无法升级", success = false)
            return
        }
        _otaInfo.value = info.copy(running = true, progress = 0, resultMessage = "", success = false)
        bleManager.startOta(scope, info.filePath,
            onProgress = { progress ->
                _otaInfo.value = _otaInfo.value.copy(progress = progress)
            },
            onFinished = { success, message ->
                if (success && info.fileVersion > 0) {
                    // 设备已重启运行新固件，更新持久化版本号，自动重连后界面显示新版本
                    settingsManager.saveLastDeviceVersion(info.fileVersion)
                }
                _otaInfo.value = _otaInfo.value.copy(
                    running = false, progress = if (success) 100 else _otaInfo.value.progress,
                    resultMessage = message, success = success
                )
            }
        )
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
