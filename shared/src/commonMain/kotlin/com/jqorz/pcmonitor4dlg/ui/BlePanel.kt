package com.jqorz.pcmonitor4dlg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jqorz.pcmonitor4dlg.model.BleConnectionState

@Composable
fun BlePanel(
    connectionState: BleConnectionState,
    deviceName: String,
    deviceVersion: Int = 0,
    onClick: () -> Unit,
    onDisconnect: () -> Unit = {},
    onUpgrade: () -> Unit = {},
    onDeviceSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val connected = connectionState == BleConnectionState.CONNECTED ||
            connectionState == BleConnectionState.SERVICE_READY

    // 标题 / 副标题随连接状态切换
    val title: String
    val subtitle: String
    when (connectionState) {
        BleConnectionState.DISCONNECTED -> {
            title = "未连接"
            subtitle = "点击选择设备"
        }
        BleConnectionState.SCANNING -> {
            title = "扫描中..."
            subtitle = "正在搜索附近的蓝牙设备"
        }
        BleConnectionState.CONNECTING -> {
            title = "连接中..."
            subtitle = deviceName
        }
        BleConnectionState.CONNECTED, BleConnectionState.SERVICE_READY -> {
            title = deviceName.ifEmpty { "蓝牙设备" }
            subtitle = "" // 已连接时不显示副标题，设备名+版本徽章单行垂直居中
        }
    }

    // 状态指示点：绿=已连接 橙=进行中 灰=未连接
    val dotColor = when (connectionState) {
        BleConnectionState.CONNECTED, BleConnectionState.SERVICE_READY -> Color(0xFF4CAF50)
        BleConnectionState.SCANNING, BleConnectionState.CONNECTING -> Color(0xFFFFA726)
        BleConnectionState.DISCONNECTED -> Color(0xFF9E9E9E)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(enabled = connectionState == BleConnectionState.DISCONNECTED) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (connected) FontWeight.Bold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // 固件版本徽章（连接后且版本已知时显示）
                    if (connected && deviceVersion > 0) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = "固件 v$deviceVersion",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 操作按钮固定在右端
            if (connectionState == BleConnectionState.SERVICE_READY) {
                TextButton(onClick = onDeviceSettings) {
                    Text("设备设置")
                }
                TextButton(onClick = onUpgrade) {
                    Text("升级")
                }
                TextButton(onClick = onDisconnect) {
                    Text("断开", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
