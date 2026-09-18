package com.jqorz.pcmonitor4dlg.ble

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary

/**
 * Windows 蓝牙开关检查（原生调用，瞬时返回，不依赖 ble_service 子进程）。
 *
 * 系统蓝牙开关关闭时 radio 设备被禁用，BluetoothFindFirstRadio 枚举不到任何
 * radio，据此判断蓝牙是否开启。库加载失败或调用异常时放行（返回 true），不阻断原有流程。
 */
object BluetoothRadio {

    // BLUETOOTH_FIND_RADIO_PARAMS { DWORD dwSize; }
    // 注意：JNA 需要反射访问这些成员，不能声明为 private，否则代理创建失败会静默走放行逻辑
    class FindRadioParams : Structure() {
        @JvmField
        var dwSize: Int = 0

        override fun getFieldOrder(): List<String> = listOf("dwSize")

        init {
            dwSize = size()
        }
    }

    interface BluetoothAPIs : StdCallLibrary {
        fun BluetoothFindFirstRadio(params: FindRadioParams, radioOut: PointerByReference): Pointer?
    }

    private val lib: BluetoothAPIs? = try {
        Native.load("bluetoothapis", BluetoothAPIs::class.java)
    } catch (e: Throwable) {
        System.err.println("[BluetoothRadio] load bluetoothapis failed: $e")
        null
    }

    /** 蓝牙已开启（存在已启用的 radio）返回 true；无法判定时也返回 true 以免误拦 */
    fun isBluetoothEnabled(): Boolean {
        val api = lib ?: return true
        val radio = PointerByReference()
        val enumHandle = try {
            api.BluetoothFindFirstRadio(FindRadioParams(), radio)
        } catch (_: Throwable) {
            return true
        } ?: return false // 枚举不到 radio：蓝牙已关闭（或本机无蓝牙适配器）

        // 释放枚举句柄和 radio 句柄，避免句柄泄漏
        try {
            Kernel32.INSTANCE.CloseHandle(WinNT.HANDLE(enumHandle))
            radio.value?.let { Kernel32.INSTANCE.CloseHandle(WinNT.HANDLE(it)) }
        } catch (_: Throwable) {
        }
        return true
    }
}
