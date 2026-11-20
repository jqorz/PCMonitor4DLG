package com.jqorz.pcmonitor4dlg.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.jqorz.pcmonitor4dlg.model.DeviceThemeAccents
import com.jqorz.pcmonitor4dlg.model.DeviceThemeBgs
import com.jqorz.pcmonitor4dlg.model.DeviceThemeDarkCount
import com.jqorz.pcmonitor4dlg.model.DeviceThemeNames

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
 * "更多"展开的独立面板（显示在蓝牙状态栏下方）：设备配色选择 + 固件升级
 */
@Composable
fun DeviceMorePanel(
    themeId: Int,
    onThemeChanged: (Int) -> Unit,
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
            // 设备配色（9套主题，色点=各主题强调色，下标即固件0xB1主题号）
            Text(
                text = "设备配色",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = "性能监控屏主题，切换立即生效，设备重启后保持",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
            )
            DeviceThemeChips(selected = themeId, onSelect = onThemeChanged)

            // 固件升级
            TextButton(onClick = onUpgrade) {
                Text("固件升级")
            }
        }
    }
}

/** 主题选择器: 全宽两列, 每项名称在左、两个预览色块(背景色+强调色)在右,
 *  选中高亮描边; 深色系与浅色系之间用分隔线区分 */
@Composable
private fun DeviceThemeChips(selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 深色背景组
        ThemeChipRows(0 until DeviceThemeDarkCount, selected, onSelect)
        // 深浅分隔线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        )
        // 浅色背景组
        ThemeChipRows(DeviceThemeDarkCount until DeviceThemeNames.size, selected, onSelect)
    }
}

@Composable
private fun ThemeChipRows(ids: IntRange, selected: Int, onSelect: (Int) -> Unit) {
    ids.toList().chunked(2).forEach { rowIds ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            rowIds.forEach { id ->
                ThemeChip(id = id, isSelected = id == selected, onSelect = onSelect, modifier = Modifier.weight(1f))
            }
            // 奇数个时补齐占位, 保持两列等宽
            if (rowIds.size == 1) Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ThemeChip(id: Int, isSelected: Boolean, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surface
            )
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable { onSelect(id) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = DeviceThemeNames[id],
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(6.dp))
        // 预览色块: 背景+强调, 居右; Color(Int)按ARGB解析需强制alpha=1(否则全透明)
        Box(
            modifier = Modifier
                .size(13.dp)
                .clip(CircleShape)
                .background(Color(DeviceThemeBgs[id]).copy(alpha = 1f))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
        )
        Box(
            modifier = Modifier
                .padding(start = 4.dp)
                .size(13.dp)
                .clip(CircleShape)
                .background(Color(DeviceThemeAccents[id]).copy(alpha = 1f))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
        )
    }
}
