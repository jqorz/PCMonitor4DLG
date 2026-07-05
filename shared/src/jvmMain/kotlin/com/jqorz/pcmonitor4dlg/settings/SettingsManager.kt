package com.jqorz.pcmonitor4dlg.settings

import com.jqorz.pcmonitor4dlg.model.AppSettings
import com.jqorz.pcmonitor4dlg.model.ExitAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.prefs.Preferences

class SettingsManager {
    companion object {
        private const val KEY_AUTO_START = "auto_start_on_boot"
        private const val KEY_AUTO_CONNECT_LAST_DEVICE = "auto_connect_last_device"
        private const val KEY_LAST_DEVICE_ADDRESS = "last_device_address"
        private const val KEY_EXIT_ACTION = "exit_action"
        private const val REG_RUN_PATH = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        private const val APP_NAME = "PCMonitor4DLG"
    }

    private val prefs: Preferences = Preferences.userRoot().node("PCMonitor4DLG")

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadSettings(): AppSettings {
        return AppSettings(
            autoStartOnBoot = prefs.getBoolean(KEY_AUTO_START, false),
            autoConnectLastDevice = prefs.getBoolean(KEY_AUTO_CONNECT_LAST_DEVICE, false),
            exitAction = ExitAction.entries[prefs.getInt(KEY_EXIT_ACTION, ExitAction.ASK.ordinal)]
        )
    }

    fun updateAutoStart(enabled: Boolean) {
        prefs.putBoolean(KEY_AUTO_START, enabled)
        setAutoStartRegistry(enabled)
        _settings.value = _settings.value.copy(autoStartOnBoot = enabled)
    }

    fun updateAutoConnectLastDevice(enabled: Boolean) {
        prefs.putBoolean(KEY_AUTO_CONNECT_LAST_DEVICE, enabled)
        _settings.value = _settings.value.copy(autoConnectLastDevice = enabled)
    }

    fun getLastDeviceAddress(): String? {
        val addr = prefs.get(KEY_LAST_DEVICE_ADDRESS, "")
        return if (addr == null || addr == "") null else addr
    }

    fun saveLastDeviceAddress(address: String) {
        prefs.put(KEY_LAST_DEVICE_ADDRESS, address)
    }

    fun updateExitAction(action: ExitAction) {
        prefs.putInt(KEY_EXIT_ACTION, action.ordinal)
        _settings.value = _settings.value.copy(exitAction = action)
    }

    private fun setAutoStartRegistry(enable: Boolean) {
        try {
            val appPath = getApplicationPath()
            val regCommand = if (enable) {
                "reg add \"HKCU\\$REG_RUN_PATH\" /v \"$APP_NAME\" /t REG_SZ /d \"\\\"$appPath\\\"\" /f"
            } else {
                "reg delete \"HKCU\\$REG_RUN_PATH\" /v \"$APP_NAME\" /f"
            }
            Runtime.getRuntime().exec(arrayOf("cmd", "/c", regCommand)).waitFor()
        } catch (_: Exception) {
            // 注册表操作失败，忽略
        }
    }

    private fun getApplicationPath(): String {
        return try {
            // 获取当前 jar/exe 的路径
            val protectionDomain = SettingsManager::class.java.protectionDomain
            val codeSource = protectionDomain.codeSource
            codeSource.location.toURI().path.let {
                if (it.startsWith("/")) it.substring(1) else it
            }
        } catch (_: Exception) {
            System.getProperty("java.class.path") ?: "PCMonitor4DLG"
        }
    }
}
