package com.jqorz.pcmonitor4dlg.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jqorz.pcmonitor4dlg.model.BleConnectionState
import com.jqorz.pcmonitor4dlg.model.OtaInfo

/**
 * OTA固件升级弹窗
 *
 * 选择 tft_monitor_ota_*.bin → 校验 → 传输（进度条）→ 设备自行重启。
 * 传输中可关闭弹窗，升级在后台继续，重新打开可看到进度。
 */
@Composable
fun OtaDialog(
    otaInfo: OtaInfo,
    connectionState: BleConnectionState,
    onSelectFile: () -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit
) {
    val canStart = otaInfo.fileValid && !otaInfo.running &&
            connectionState == BleConnectionState.SERVICE_READY

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "固件升级 (OTA)",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 320.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 固件文件信息
                if (otaInfo.filePath.isEmpty()) {
                    Text(
                        text = "未选择固件文件。请选择 tft_monitor_ota_*.bin（构建后自动拷贝到桌面），不要选整刷镜像",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "固件：${otaInfo.fileName}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (otaInfo.fileValid) {
                            "版本 v${otaInfo.fileVersion}，${otaInfo.fileSize} 字节"
                        } else {
                            otaInfo.fileError
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (otaInfo.fileValid) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }

                // 传输进度
                if (otaInfo.running) {
                    LinearProgressIndicator(
                        progress = { otaInfo.progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "正在传输 ${otaInfo.progress}%（期间屏幕冻结属正常）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 结果信息
                if (otaInfo.resultMessage.isNotEmpty()) {
                    Text(
                        text = otaInfo.resultMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (otaInfo.success) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }

                if (otaInfo.fileValid && !otaInfo.running &&
                    connectionState != BleConnectionState.SERVICE_READY && otaInfo.resultMessage.isEmpty()
                ) {
                    Text(
                        text = "设备未连接，请先连接设备",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onSelectFile,
                    enabled = !otaInfo.running
                ) {
                    Text("选择固件")
                }
                TextButton(
                    onClick = onStart,
                    enabled = canStart
                ) {
                    Text("开始升级")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
