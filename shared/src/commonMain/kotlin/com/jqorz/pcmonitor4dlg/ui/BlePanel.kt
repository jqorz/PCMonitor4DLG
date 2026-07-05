package com.jqorz.pcmonitor4dlg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jqorz.pcmonitor4dlg.model.BleConnectionState

@Composable
fun BlePanel(
    connectionState: BleConnectionState,
    deviceName: String,
    onClick: () -> Unit,
    onDisconnect: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable { onClick() }
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "📡", style = MaterialTheme.typography.titleLarge)

            when (connectionState) {
                BleConnectionState.DISCONNECTED -> {
                    Text(
                        text = "未连接 - 点击选择设备",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                BleConnectionState.SCANNING -> {
                    Text(
                        text = "扫描中...",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                BleConnectionState.CONNECTING -> {
                    Text(
                        text = "连接中: $deviceName",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                BleConnectionState.CONNECTED -> {
                    Text(
                        text = "已连接: $deviceName",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                BleConnectionState.SERVICE_READY -> {
                    Text(
                        text = "📡 $deviceName [已连接]",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(onClick = onDisconnect) {
                        Text("断开", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
