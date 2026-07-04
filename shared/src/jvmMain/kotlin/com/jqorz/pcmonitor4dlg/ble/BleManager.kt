package com.jqorz.pcmonitor4dlg.ble

import com.jqorz.pcmonitor4dlg.model.BleConnectionState
import com.jqorz.pcmonitor4dlg.model.BleDeviceInfo
import com.jqorz.pcmonitor4dlg.model.SystemStats
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar

class BleManager {

    companion object {
        val SERVICE_UUID: GUID = GUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
        val CTRL_POINT_UUID: GUID = GUID.fromString("0000ff03-0000-1000-8000-00805f9b34fb")
        val ADC1_VALUE_UUID: GUID = GUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        val LONG_VALUE_UUID: GUID = GUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")

        private const val ERROR_SUCCESS = 0
        // BTH_LE_GATT_SERVICE 结构体大小: GUID(16) + Short(2) + Short(2) = 20
        private const val SERVICE_STRUCT_SIZE = 20
        // BTH_LE_GATT_CHARACTERISTIC 结构体大小: GUID(16) + Short(2) + Short(2) + Short(2) = 22
        private const val CHAR_STRUCT_SIZE = 22
    }

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow("")
    val connectedDeviceName: StateFlow<String> = _connectedDeviceName.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<BleDeviceInfo>>(emptyList())
    val scannedDevices: StateFlow<List<BleDeviceInfo>> = _scannedDevices.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private var scanJob: Job? = null
    private var syncJob: Job? = null
    private var gattHandle: WinNT.HANDLE? = null
    private var longValueAttrHandle: Short = 0
    private var ctrlPointAttrHandle: Short = 0

    fun startScan(scope: CoroutineScope, filterDlg: Boolean = true) {
        stopScan()
        _connectionState.value = BleConnectionState.SCANNING
        _scannedDevices.value = emptyList()
        addLog("开始扫描蓝牙设备...")

        scanJob = scope.launch(Dispatchers.IO) {
            val devices = scanBluetoothDevices(filterDlg)
            _scannedDevices.value = devices
            _connectionState.value = BleConnectionState.DISCONNECTED
            addLog("扫描完成，找到 ${devices.size} 个设备")
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (_connectionState.value == BleConnectionState.SCANNING) {
            _connectionState.value = BleConnectionState.DISCONNECTED
        }
    }

    fun connect(scope: CoroutineScope, device: BleDeviceInfo) {
        disconnect()
        _connectionState.value = BleConnectionState.CONNECTING
        _connectedDeviceName.value = device.name
        addLog("正在连接: ${device.name} (${device.address})")

        scope.launch(Dispatchers.IO) {
            try {
                val addressBytes = parseBluetoothAddress(device.address)
                if (addressBytes == null) {
                    addLog("地址解析失败")
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    return@launch
                }

                // 打开 GATT 设备
                val addrMem = Memory(8)
                addrMem.write(0, addressBytes, 0, 6)
                addrMem.setByte(6, 0)
                addrMem.setByte(7, 0)

                val handleRef = PointerByReference()
                val openResult = BluetoothGattApi.INSTANCE.BluetoothGATTOpenDevice(addrMem, handleRef)
                if (openResult != ERROR_SUCCESS) {
                    addLog("无法打开 GATT 设备: error=$openResult")
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    return@launch
                }

                gattHandle = WinNT.HANDLE(handleRef.value)
                _connectionState.value = BleConnectionState.CONNECTED
                addLog("已连接，正在发现服务...")

                // 发现服务
                val serviceCount = IntByReference()
                val result = BluetoothGattApi.INSTANCE.BluetoothGATTGetServices(
                    gattHandle!!, 0, null, serviceCount, 0
                )
                if (result != ERROR_SUCCESS || serviceCount.value == 0) {
                    addLog("服务发现失败: result=$result count=${serviceCount.value}")
                    disconnect()
                    return@launch
                }

                val bufSize = (serviceCount.value * SERVICE_STRUCT_SIZE).toShort()
                val servicesBuf = Memory(bufSize.toLong())
                val result2 = BluetoothGattApi.INSTANCE.BluetoothGATTGetServices(
                    gattHandle!!, bufSize, servicesBuf, serviceCount, 0
                )
                if (result2 != ERROR_SUCCESS) {
                    addLog("读取服务列表失败: $result2")
                    disconnect()
                    return@launch
                }

                // 查找目标服务
                var serviceOffset = 0L
                var foundService = false
                for (i in 0 until serviceCount.value) {
                    val svcUuid = GUID(servicesBuf.share(serviceOffset))
                    if (svcUuid == SERVICE_UUID) {
                        addLog("找到目标服务")
                        foundService = true
                        val numChars = servicesBuf.getShort(serviceOffset + 18)
                        discoverCharacteristics(servicesBuf.share(serviceOffset), numChars)
                        break
                    }
                    serviceOffset += SERVICE_STRUCT_SIZE
                }

                if (!foundService) {
                    addLog("未找到目标 GATT 服务")
                    disconnect()
                    return@launch
                }

                _connectionState.value = BleConnectionState.SERVICE_READY
                addLog("服务就绪")

            } catch (e: Exception) {
                addLog("连接异常: ${e.message}")
                _connectionState.value = BleConnectionState.DISCONNECTED
            }
        }
    }

    private fun discoverCharacteristics(servicePtr: Pointer, numChars: Short) {
        if (numChars.toInt() == 0) return

        val charCount = IntByReference()
        val result = BluetoothGattApi.INSTANCE.BluetoothGATTGetCharacteristics(
            gattHandle!!, servicePtr, 0, null, charCount, 0
        )
        if (result != ERROR_SUCCESS || charCount.value == 0) {
            addLog("特征值发现失败: $result")
            return
        }

        val bufSize = (charCount.value * CHAR_STRUCT_SIZE).toShort()
        val charsBuf = Memory(bufSize.toLong())
        val result2 = BluetoothGattApi.INSTANCE.BluetoothGATTGetCharacteristics(
            gattHandle!!, servicePtr, bufSize, charsBuf, charCount, 0
        )
        if (result2 != ERROR_SUCCESS) {
            addLog("读取特征值列表失败: $result2")
            return
        }

        var offset = 0L
        for (i in 0 until charCount.value) {
            val charUuid = GUID(charsBuf.share(offset))
            val valueHandle = charsBuf.getShort(offset + 20) // valueHandle 在结构体末尾

            if (charUuid == LONG_VALUE_UUID) {
                longValueAttrHandle = valueHandle
                addLog("找到 Long Value 特征值 (handle=$valueHandle)")
            } else if (charUuid == CTRL_POINT_UUID) {
                ctrlPointAttrHandle = valueHandle
                addLog("找到 Control Point 特征值 (handle=$valueHandle)")
            }
            offset += CHAR_STRUCT_SIZE
        }
    }

    fun syncTime(scope: CoroutineScope) {
        if (_connectionState.value != BleConnectionState.SERVICE_READY) {
            addLog("设备未就绪")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                addLog("同步时间中...")

                val startTime = System.currentTimeMillis()
                val waitTime = 1000 - (startTime % 1000)
                if (waitTime > 10) delay(waitTime)

                val now = Calendar.getInstance()
                val year = now.get(Calendar.YEAR)
                val month = now.get(Calendar.MONTH)
                val day = now.get(Calendar.DAY_OF_MONTH)
                val hour = now.get(Calendar.HOUR_OF_DAY)
                val minute = now.get(Calendar.MINUTE)
                val second = now.get(Calendar.SECOND)
                val wday = now.get(Calendar.DAY_OF_WEEK) - 1

                addLog(String.format("准备同步: %d-%02d-%02d %02d:%02d:%02d wday=%d",
                    year, month + 1, day, hour, minute, second, wday))

                val buf = ByteArray(12)
                buf[0] = 0x91.toByte()
                buf[1] = (year % 256).toByte()
                buf[2] = (year / 256).toByte()
                buf[3] = month.toByte()
                buf[4] = day.toByte()
                buf[5] = hour.toByte()
                buf[6] = minute.toByte()
                buf[7] = second.toByte()
                buf[8] = wday.toByte()
                buf[9] = 0
                buf[10] = 0
                buf[11] = 0

                writeCharacteristicValue(longValueAttrHandle, buf)
                addLog("时间同步完成")
            } catch (e: Exception) {
                addLog("同步失败: ${e.message}")
            }
        }
    }

    fun startDataSync(scope: CoroutineScope, statsProvider: () -> SystemStats) {
        stopDataSync()
        syncJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (_connectionState.value == BleConnectionState.SERVICE_READY) {
                    try {
                        val stats = statsProvider()
                        val buf = ByteArray(20)
                        buf[0] = 0x93.toByte()
                        buf[1] = stats.cpuTemp.toInt().toByte()
                        buf[2] = stats.cpuUsage.toInt().toByte()
                        buf[3] = stats.gpuTemp.toInt().toByte()
                        buf[4] = stats.gpuUsage.toInt().toByte()
                        buf[5] = stats.memUsage.toInt().toByte()
                        val upKB = (stats.netUpSpeed / 1024).toInt().coerceIn(0, 65535)
                        val downKB = (stats.netDownSpeed / 1024).toInt().coerceIn(0, 65535)
                        buf[6] = (upKB and 0xFF).toByte()
                        buf[7] = ((upKB shr 8) and 0xFF).toByte()
                        buf[8] = (downKB and 0xFF).toByte()
                        buf[9] = ((downKB shr 8) and 0xFF).toByte()
                        writeCharacteristicValue(longValueAttrHandle, buf)
                    } catch (_: Exception) {
                    }
                }
                delay(1000)
            }
        }
    }

    fun stopDataSync() {
        syncJob?.cancel()
        syncJob = null
    }

    private fun writeCharacteristicValue(attrHandle: Short, data: ByteArray) {
        val handle = gattHandle ?: return
        val dataMem = Memory(data.size.toLong())
        dataMem.write(0, data, 0, data.size)

        val result = BluetoothGattApi.INSTANCE.BluetoothGATTSetCharacteristicValue(
            handle, attrHandle, dataMem, null, 0
        )

        if (result != ERROR_SUCCESS) {
            addLog("写入失败: error=$result")
        }
    }

    fun disconnect() {
        stopDataSync()
        gattHandle?.let { handle ->
            BluetoothGattApi.INSTANCE.BluetoothGATTCloseDevice(handle)
        }
        gattHandle = null
        longValueAttrHandle = 0
        ctrlPointAttrHandle = 0
        _connectionState.value = BleConnectionState.DISCONNECTED
        _connectedDeviceName.value = ""
        addLog("设备已断开")
    }

    fun clearLog() {
        _logs.value = emptyList()
    }

    private fun addLog(msg: String) {
        val now = Calendar.getInstance()
        val timestamp = String.format("%02d:%02d:%02d",
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            now.get(Calendar.SECOND))
        _logs.value = _logs.value + "[$timestamp] $msg"
    }

    private fun scanBluetoothDevices(filterDlg: Boolean): List<BleDeviceInfo> {
        val devices = mutableListOf<BleDeviceInfo>()
        try {
            val searchParams = BLUETOOTH_DEVICE_SEARCH_PARAMS()
            searchParams.dwSize = searchParams.size()
            searchParams.fReturnAuthenticated = true
            searchParams.fReturnRemembered = true
            searchParams.fReturnUnknown = true
            searchParams.fReturnConnected = true
            searchParams.fIssueInquiry = true
            searchParams.cTimeoutMultiplier = 2

            val deviceInfo = BLUETOOTH_DEVICE_INFO()
            deviceInfo.dwSize = deviceInfo.size()

            val findHandle = BluetoothApis.INSTANCE.BluetoothFindFirstDevice(searchParams, deviceInfo)

            if (findHandle != null && findHandle != Pointer.NULL) {
                do {
                    val name = String(deviceInfo.szName).trim(' ')
                    val addr = deviceInfo.address.rgBytes
                    val address = String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                        addr[5], addr[4], addr[3], addr[2], addr[1], addr[0])

                    if (!filterDlg || name.startsWith("DLG", ignoreCase = true)) {
                        devices.add(BleDeviceInfo(name, address))
                    }

                    deviceInfo.dwSize = deviceInfo.size()
                } while (BluetoothApis.INSTANCE.BluetoothFindNextDevice(findHandle, deviceInfo))

                BluetoothApis.INSTANCE.BluetoothFindDeviceClose(findHandle)
            }
        } catch (e: Exception) {
            addLog("扫描异常: ${e.message}")
        }
        return devices
    }

    private fun parseBluetoothAddress(address: String): ByteArray? {
        return try {
            val parts = address.split(":").reversed()
            parts.map { it.toLong(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            null
        }
    }
}
