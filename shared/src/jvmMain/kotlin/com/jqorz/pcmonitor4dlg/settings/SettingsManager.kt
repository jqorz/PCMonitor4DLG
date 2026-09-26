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
        private const val KEY_LAST_DEVICE_NAME = "last_device_name"
        private const val KEY_LAST_DEVICE_VERSION = "last_device_version"
        private const val KEY_EXIT_ACTION = "exit_action"
        private const val KEY_DEVICE_THEME_DARK = "device_theme_dark"
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
            deviceThemeDark = prefs.getBoolean(KEY_DEVICE_THEME_DARK, true),
            exitAction = ExitAction.entries[prefs.getInt(KEY_EXIT_ACTION, ExitAction.ASK.ordinal)]
        )
    }

    fun updateAutoStart(enabled: Boolean) {
        if (enabled) {
            // 只有打包后的启动器(PCMonitor.exe)能注册自启；开发环境(gradlew run/IDE)是 java.exe，无意义
            val launcherExe = getLauncherExe() ?: return
            if (!setAutoStartRegistry(true, launcherExe)) return
        } else {
            setAutoStartRegistry(false, "")
        }
        prefs.putBoolean(KEY_AUTO_START, enabled)
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

    /** 上次成功连接设备的名称（与地址配对保存），供按地址重连时显示，避免闪现/长显 MAC */
    fun getLastDeviceName(): String? {
        val name = prefs.get(KEY_LAST_DEVICE_NAME, "")
        return if (name == null || name == "") null else name
    }

    fun saveLastDeviceName(name: String) {
        prefs.put(KEY_LAST_DEVICE_NAME, name)
    }

    /** 上次成功连接设备的固件版本号（与地址配对保存），0 表示未知 */
    fun getLastDeviceVersion(): Int = prefs.getInt(KEY_LAST_DEVICE_VERSION, 0)

    fun saveLastDeviceVersion(version: Int) {
        prefs.putInt(KEY_LAST_DEVICE_VERSION, version)
    }

    /** 设备主题：true=深色(黑底白字) false=浅色(白底黑字)，连接时同步给设备(0xB1) */
    fun updateDeviceTheme(dark: Boolean) {
        prefs.putBoolean(KEY_DEVICE_THEME_DARK, dark)
        _settings.value = _settings.value.copy(deviceThemeDark = dark)
    }

    fun updateExitAction(action: ExitAction) {
        prefs.putInt(KEY_EXIT_ACTION, action.ordinal)
        _settings.value = _settings.value.copy(exitAction = action)
    }

    private fun setAutoStartRegistry(enable: Boolean, exePath: String): Boolean {
        return try {
            // 直接调 reg.exe，避免 cmd /c 拼接引号被二次解析
            val command = if (enable) {
                listOf(
                    "reg", "add", "HKCU\\$REG_RUN_PATH",
                    "/v", APP_NAME, "/t", "REG_SZ", "/d", "\"$exePath\"", "/f"
                )
            } else {
                listOf("reg", "delete", "HKCU\\$REG_RUN_PATH", "/v", APP_NAME, "/f")
            }
            val process = ProcessBuilder(command).start()
            process.waitFor() == 0
        } catch (_: Exception) {
            // 注册表操作失败，忽略
            false
        }
    }

    /** 打包后的启动器进程就是 PCMonitor.exe 本身；开发环境是 java.exe，返回 null */
    private fun getLauncherExe(): String? {
        return try {
            ProcessHandle.current().info().command().orElse(null)
        } catch (_: Exception) {
            null
        }?.takeIf { it.endsWith("pcmonitor.exe", ignoreCase = true) }
    }
}
