package com.jqorz.pcmonitor4dlg.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jqorz.pcmonitor4dlg.model.AppSettings

@Composable
fun SettingsDialog(
    settings: AppSettings,
    onAutoStartChanged: (Boolean) -> Unit,
    onMinimizeOnStartupChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "设置",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 300.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 开机自启
                SettingSwitch(
                    label = "开机自启",
                    description = "应用随 Windows 系统开机自动启动",
                    checked = settings.autoStartOnBoot,
                    onCheckedChange = onAutoStartChanged
                )

                HorizontalDivider()

                // 启动后最小化
                SettingSwitch(
                    label = "启动后最小化到任务栏",
                    description = "应用启动时不显示主窗口，直接最小化到系统托盘",
                    checked = settings.minimizeOnStartup,
                    onCheckedChange = onMinimizeOnStartupChanged
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("确定")
            }
        }
    )
}

@Composable
private fun SettingSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
