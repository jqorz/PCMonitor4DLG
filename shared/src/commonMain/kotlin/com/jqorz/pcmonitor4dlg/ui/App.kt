package com.jqorz.pcmonitor4dlg.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jqorz.pcmonitor4dlg.model.BleConnectionState
import com.jqorz.pcmonitor4dlg.model.ExitAction
import com.jqorz.pcmonitor4dlg.viewmodel.MainViewModel

@Composable
fun App(
    viewModel: MainViewModel,
    onMinimizeToTray: () -> Unit = {},
    onExit: () -> Unit = {}
) {
    val systemStats by viewModel.systemStats.collectAsState()
    val connectionState by viewModel.bleConnectionState.collectAsState()
    val connectedDeviceName by viewModel.connectedDeviceName.collectAsState()
    val connectedDeviceVersion by viewModel.connectedDeviceVersion.collectAsState()
    val scannedDevices by viewModel.scannedDevices.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val showSettingsDialog by viewModel.showSettingsDialog.collectAsState()
    val showDevicePicker by viewModel.showDevicePicker.collectAsState()
    val showExitDialog by viewModel.showExitDialog.collectAsState()
    val showOtaDialog by viewModel.showOtaDialog.collectAsState()
    val otaInfo by viewModel.otaInfo.collectAsState()
    val showBluetoothOffDialog by viewModel.showBluetoothOffDialog.collectAsState()

    var filterDlg by remember { mutableStateOf(true) }
    // "更多"独立面板展开状态（纯UI状态；断开连接时由可见条件自动收起）
    var showMorePanel by remember { mutableStateOf(false) }
    val isScanning = connectionState == BleConnectionState.SCANNING

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 顶部栏：标题 + 设置按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PCMonitor4DLG",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    IconButton(onClick = { viewModel.setShowSettingsDialog(true) }) {
                        Text("⚙", style = MaterialTheme.typography.titleLarge)
                    }
                }

                // 监控区域
                MonitorPanel(
                    stats = systemStats,
                    modifier = Modifier.weight(1f)
                )

                // 蓝牙状态区域
                BlePanel(
                    connectionState = connectionState,
                    deviceName = connectedDeviceName,
                    deviceVersion = connectedDeviceVersion,
                    moreExpanded = showMorePanel,
                    onClick = {
                        if (connectionState == BleConnectionState.DISCONNECTED) {
                            viewModel.setShowDevicePicker(true)
                        }
                    },
                    onDisconnect = { viewModel.disconnectDevice() },
                    onMore = { showMorePanel = !showMorePanel },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                // "更多"独立面板：仅连接就绪时显示，断开自动收起
                AnimatedVisibility(
                    visible = showMorePanel && connectionState == BleConnectionState.SERVICE_READY,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    DeviceMorePanel(
                        themeDark = settings.deviceThemeDark,
                        onThemeChanged = { viewModel.updateDeviceTheme(it) },
                        onUpgrade = {
                            if (connectionState == BleConnectionState.SERVICE_READY) {
                                viewModel.setShowOtaDialog(true)
                            }
                        },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                    )
                }
            }
        }

        // 设备选择弹窗
        if (showDevicePicker) {
            // 在 UI 层根据勾选框过滤设备，切换即时生效
            val filteredDevices = if (filterDlg) {
                scannedDevices.filter { it.name.contains("PCM", ignoreCase = true) }
            } else {
                scannedDevices
            }

            DevicePickerDialog(
                devices = filteredDevices,
                connectionState = connectionState,
                filterDlg = filterDlg,
                isScanning = isScanning,
                onFilterChanged = { filterDlg = it },
                onScan = { viewModel.startScan() },
                onStopScan = { viewModel.stopScan() },
                onSelectDevice = { device ->
                    viewModel.connectDevice(device)
                    viewModel.setShowDevicePicker(false)
                },
                onDismiss = { viewModel.setShowDevicePicker(false) }
            )
        }

        // OTA固件升级弹窗
        if (showOtaDialog) {
            OtaDialog(
                otaInfo = otaInfo,
                connectionState = connectionState,
                onSelectFile = { viewModel.selectOtaFile() },
                onStart = { viewModel.startOtaUpgrade() },
                onDismiss = { viewModel.setShowOtaDialog(false) }
            )
        }

        // 设置弹窗
        if (showSettingsDialog) {
            SettingsDialog(
                settings = settings,
                appVersion = viewModel.appVersion,
                onAutoStartChanged = { viewModel.updateAutoStart(it) },
                onAutoConnectLastDeviceChanged = { viewModel.updateAutoConnectLastDevice(it) },
                onDismiss = { viewModel.setShowSettingsDialog(false) }
            )
        }

        // 蓝牙未开启提示弹窗
        if (showBluetoothOffDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.setShowBluetoothOffDialog(false) },
                title = { Text("蓝牙未开启", fontWeight = FontWeight.Bold) },
                text = { Text("请先打开电脑的蓝牙开关，开启后再选择设备。") },
                confirmButton = {
                    TextButton(onClick = { viewModel.setShowBluetoothOffDialog(false) }) {
                        Text("确定")
                    }
                }
            )
        }

        // 退出确认弹窗
        if (showExitDialog) {
            ExitDialog(
                onMinimize = { remember ->
                    if (remember) viewModel.updateExitAction(ExitAction.MINIMIZE)
                    viewModel.setShowExitDialog(false)
                    onMinimizeToTray()
                },
                onExit = { remember ->
                    if (remember) viewModel.updateExitAction(ExitAction.EXIT)
                    viewModel.setShowExitDialog(false)
                    viewModel.performExit()
                    onExit()
                },
                onDismiss = { viewModel.setShowExitDialog(false) }
            )
        }
    }
}
