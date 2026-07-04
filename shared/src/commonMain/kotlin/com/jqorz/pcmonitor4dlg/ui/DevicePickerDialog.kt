package com.jqorz.pcmonitor4dlg.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jqorz.pcmonitor4dlg.model.BleConnectionState
import com.jqorz.pcmonitor4dlg.model.BleDeviceInfo

@Composable
fun DevicePickerDialog(
    devices: List<BleDeviceInfo>,
    connectionState: BleConnectionState,
    filterDlg: Boolean,
    isScanning: Boolean,
    onFilterChanged: (Boolean) -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onSelectDevice: (BleDeviceInfo) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            onStopScan()
            onDismiss()
        },
        title = {
            Text("选择蓝牙设备", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 320.dp, max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 过滤选项
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = filterDlg,
                        onCheckedChange = onFilterChanged
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("只显示 DLG 开头的设备", style = MaterialTheme.typography.bodyMedium)
                }

                HorizontalDivider()

                // 设备列表
                if (isScanning) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("正在扫描...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else if (devices.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("未发现设备", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(devices) { device ->
                            DeviceItem(
                                device = device,
                                isConnecting = connectionState == BleConnectionState.CONNECTING,
                                onClick = { onSelectDevice(device) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (isScanning) onStopScan() else onScan()
            }) {
                Text(if (isScanning) "停止扫描" else "扫描")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onStopScan()
                onDismiss()
            }) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun DeviceItem(
    device: BleDeviceInfo,
    isConnecting: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !isConnecting) { onClick() },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = device.name.ifEmpty { "未知设备" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
