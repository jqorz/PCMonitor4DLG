package com.jqorz.pcmonitor4dlg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jqorz.pcmonitor4dlg.model.SystemStats

@Composable
fun MonitorPanel(stats: SystemStats, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // CPU + 内存
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MonitorCard("CPU 占用", String.format("%.1f%%", stats.cpuUsage), Modifier.weight(1f))
            MonitorCard("内存占用", String.format("%.1f%%", stats.memUsage), Modifier.weight(1f))
        }

        // GPU 行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MonitorCard("GPU 温度", String.format("%.0f°C", stats.gpuTemp), Modifier.weight(1f))
            MonitorCard("GPU 占用", String.format("%.1f%%", stats.gpuUsage), Modifier.weight(1f))
        }

        // 网速
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MonitorCard("↑ 上行", stats.formatNetSpeed(stats.netUpSpeed), Modifier.weight(1f))
            MonitorCard("↓ 下行", stats.formatNetSpeed(stats.netDownSpeed), Modifier.weight(1f))
        }
    }
}

@Composable
private fun MonitorCard(label: String, value: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
