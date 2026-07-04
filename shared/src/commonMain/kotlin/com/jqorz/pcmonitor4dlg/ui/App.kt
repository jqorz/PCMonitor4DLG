package com.jqorz.pcmonitor4dlg.ui

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
    val scannedDevices by viewModel.scannedDevices.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val showSettingsDialog by viewModel.showSettingsDialog.collectAsState()
    val showDevicePicker by viewModel.showDevicePicker.collectAsState()
    val showExitDialog by viewModel.showExitDialog.collectAsState()

    var filterDlg by remember { mutableStateOf(true) }
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
                    onClick = {
                        if (connectionState == BleConnectionState.DISCONNECTED) {
                            viewModel.setShowDevicePicker(true)
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // 设备选择弹窗
        if (showDevicePicker) {
            DevicePickerDialog(
                devices = scannedDevices,
                connectionState = connectionState,
                filterDlg = filterDlg,
                isScanning = isScanning,
                onFilterChanged = { filterDlg = it },
                onScan = { viewModel.startScan(filterDlg) },
                onStopScan = { viewModel.stopScan() },
                onSelectDevice = { device ->
                    viewModel.connectDevice(device)
                    viewModel.setShowDevicePicker(false)
                },
                onDismiss = { viewModel.setShowDevicePicker(false) }
            )
        }

        // 设置弹窗
        if (showSettingsDialog) {
            SettingsDialog(
                settings = settings,
                onAutoStartChanged = { viewModel.updateAutoStart(it) },
                onMinimizeOnStartupChanged = { viewModel.updateMinimizeOnStartup(it) },
                onDismiss = { viewModel.setShowSettingsDialog(false) }
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
