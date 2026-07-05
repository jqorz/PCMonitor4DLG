package com.jqorz.pcmonitor4dlg.model

enum class ExitAction {
    ASK, MINIMIZE, EXIT
}

data class AppSettings(
    val autoStartOnBoot: Boolean = false,
    val autoConnectLastDevice: Boolean = false,
    val exitAction: ExitAction = ExitAction.ASK
)
