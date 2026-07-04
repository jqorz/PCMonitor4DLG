package com.jqorz.pcmonitor4dlg.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jqorz.pcmonitor4dlg.model.ExitAction

@Composable
fun ExitDialog(
    onMinimize: (remember: Boolean) -> Unit,
    onExit: (remember: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedAction by remember { mutableStateOf(ExitAction.MINIMIZE) }
    var rememberChoice by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "确认退出？",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 280.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = selectedAction == ExitAction.MINIMIZE,
                            onClick = { selectedAction = ExitAction.MINIMIZE }
                        )
                        Text("最小化到托盘", style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = selectedAction == ExitAction.EXIT,
                            onClick = { selectedAction = ExitAction.EXIT }
                        )
                        Text("退出应用", style = MaterialTheme.typography.bodyLarge)
                    }
                }

                HorizontalDivider()

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = rememberChoice,
                        onCheckedChange = { rememberChoice = it }
                    )
                    Text("不再询问，记住我的选择", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (selectedAction) {
                    ExitAction.MINIMIZE -> onMinimize(rememberChoice)
                    ExitAction.EXIT -> onExit(rememberChoice)
                    else -> {}
                }
            }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
