package com.jqorz.pcmonitor4dlg.ble

import com.sun.jna.*
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.W32APIOptions

// ---- Windows Bluetooth Device API (bthprops.cpl) ----

@Structure.FieldOrder("dwSize", "fReturnAuthenticated", "fReturnRemembered",
    "fReturnUnknown", "fReturnConnected", "fIssueInquiry", "cTimeoutMultiplier", "hRadio")
open class BLUETOOTH_DEVICE_SEARCH_PARAMS : Structure() {
    @JvmField var dwSize: Int = 0
    @JvmField var fReturnAuthenticated: Boolean = false
    @JvmField var fReturnRemembered: Boolean = false
    @JvmField var fReturnUnknown: Boolean = false
    @JvmField var fReturnConnected: Boolean = false
    @JvmField var fIssueInquiry: Boolean = false
    @JvmField var cTimeoutMultiplier: Byte = 0
    @JvmField var hRadio: Pointer? = null
}

@Structure.FieldOrder("rgBytes")
open class BLUETOOTH_ADDRESS : Structure() {
    @JvmField var rgBytes: ByteArray = ByteArray(8) // union with ULONGLONG
}

@Structure.FieldOrder("wYear", "wMonth", "wDayOfWeek", "wDay",
    "wHour", "wMinute", "wSecond", "wMilliseconds")
open class SYSTEMTIME : Structure() {
    @JvmField var wYear: Short = 0
    @JvmField var wMonth: Short = 0
    @JvmField var wDayOfWeek: Short = 0
    @JvmField var wDay: Short = 0
    @JvmField var wHour: Short = 0
    @JvmField var wMinute: Short = 0
    @JvmField var wSecond: Short = 0
    @JvmField var wMilliseconds: Short = 0
}

// BLUETOOTH_MAX_NAME_SIZE = 248
@Structure.FieldOrder("dwSize", "address", "dwClassOfDevice", "fConnected",
    "fRemembered", "fAuthenticated", "stLastSeen", "stLastUsed", "szName")
open class BLUETOOTH_DEVICE_INFO : Structure() {
    @JvmField var dwSize: Int = 0
    @JvmField var address: BLUETOOTH_ADDRESS = BLUETOOTH_ADDRESS()
    @JvmField var dwClassOfDevice: Int = 0
    @JvmField var fConnected: Boolean = false
    @JvmField var fRemembered: Boolean = false
    @JvmField var fAuthenticated: Boolean = false
    @JvmField var stLastSeen: SYSTEMTIME = SYSTEMTIME()
    @JvmField var stLastUsed: SYSTEMTIME = SYSTEMTIME()
    @JvmField var szName: CharArray = CharArray(248)
}

interface BluetoothApis : Library {
    companion object {
        val INSTANCE: BluetoothApis by lazy {
            // bthprops.cpl 是 .cpl 文件，JNA 默认搜索不到，需要指定完整路径
            val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
            val cplPath = "$systemRoot\\System32\\bthprops.cpl"
            try {
                Native.load(cplPath, BluetoothApis::class.java, W32APIOptions.DEFAULT_OPTIONS)
            } catch (_: Exception) {
                // 回退：尝试 BluetoothAPIs.dll（Win10+）
                Native.load("BluetoothAPIs.dll", BluetoothApis::class.java, W32APIOptions.DEFAULT_OPTIONS)
            }
        }
    }

    fun BluetoothFindFirstDevice(
        pbtsp: BLUETOOTH_DEVICE_SEARCH_PARAMS,
        pbtdi: BLUETOOTH_DEVICE_INFO
    ): Pointer?

    fun BluetoothFindNextDevice(hFind: Pointer, pbtdi: BLUETOOTH_DEVICE_INFO): Boolean
    fun BluetoothFindDeviceClose(hFind: Pointer): Boolean
}

// ---- Windows Bluetooth GATT API (BluetoothAPIs.dll) ----

interface BluetoothGattApi : Library {
    companion object {
        val INSTANCE: BluetoothGattApi = Native.load(
            "BluetoothAPIs.dll", BluetoothGattApi::class.java, W32APIOptions.DEFAULT_OPTIONS
        )
    }

    // Opens a Bluetooth GATT device handle from a 6-byte Bluetooth address
    fun BluetoothGATTOpenDevice(
        pAddress: Pointer,
        pHandle: PointerByReference
    ): Int

    fun BluetoothGATTCloseDevice(handle: WinNT.HANDLE): Int

    // Get services: returns BTH_LE_GATT_SERVICE array
    fun BluetoothGATTGetServices(
        handle: WinNT.HANDLE,
        servicesBufferCount: Short,
        servicesBuffer: Pointer?,
        servicesCountOut: IntByReference,
        flags: Int
    ): Int

    // Get characteristics for a service (pService is a pointer to BTH_LE_GATT_SERVICE)
    fun BluetoothGATTGetCharacteristics(
        handle: WinNT.HANDLE,
        servicePtr: Pointer,
        characteristicsBufferCount: Short,
        characteristicsBuffer: Pointer?,
        characteristicsCountOut: IntByReference,
        flags: Int
    ): Int

    // Set (write) characteristic value
    fun BluetoothGATTSetCharacteristicValue(
        handle: WinNT.HANDLE,
        characteristicHandle: Short,
        characterisitcValue: Pointer?,
        outSize: Short?,
        flags: Int
    ): Int

    // Register for characteristic value change events
    fun BluetoothGATTRegisterEvent(
        handle: WinNT.HANDLE,
        eventType: Int,
        eventParameterIn: Pointer?,
        callback: Callback,
        context: Pointer?,
        eventParameterOut: PointerByReference,
        flags: Int
    ): Int

    fun BluetoothGATTUnregisterEvent(eventParameterOut: PointerByReference): Int
}
