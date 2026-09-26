package com.jqorz.pcmonitor4dlg.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    moreExpanded: Boolean = false,
    onClick: () -> Unit,
    onDisconnect: () -> Unit = {},
    onMore: () -> Unit = {},
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

            // 操作按钮固定在右端："更多"切换下方独立面板的展开，"断开"常驻
            if (connectionState == BleConnectionState.SERVICE_READY) {
                TextButton(onClick = onMore) {
                    Text(if (moreExpanded) "收起" else "更多")
                }
                TextButton(onClick = onDisconnect) {
                    Text("断开", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * "更多"展开的独立面板（显示在蓝牙状态栏下方）：深色模式切换 + 固件升级
 */
@Composable
fun DeviceMorePanel(
    themeDark: Boolean,
    onThemeChanged: (Boolean) -> Unit,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 深色模式
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "深色模式",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "性能监控屏配色：开启=黑底白字，关闭=白底黑字。切换立即生效，设备重启后保持",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Switch(checked = themeDark, onCheckedChange = onThemeChanged)
            }

            // 固件升级
            TextButton(onClick = onUpgrade) {
                Text("固件升级")
            }
        }
    }
}
