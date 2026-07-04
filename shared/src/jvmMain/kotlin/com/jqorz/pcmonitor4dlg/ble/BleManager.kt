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
        private const val SERVICE_STRUCT_SIZE = 20
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

    fun startScan(scope: CoroutineScope) {
        stopScan()
        _connectionState.value = BleConnectionState.SCANNING
        _scannedDevices.value = emptyList()
        addLog("开始扫描蓝牙设备...")

        scanJob = scope.launch(Dispatchers.IO) {
            try {
                val startTime = System.currentTimeMillis()
                val devices = scanBluetoothDevices()
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed < 2000) delay(2000 - elapsed)
                _scannedDevices.value = devices
                addLog("扫描完成，找到 ${devices.size} 个设备")
            } catch (e: Exception) {
                addLog("扫描失败: ${e.message}")
            } finally {
                _connectionState.value = BleConnectionState.DISCONNECTED
            }
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
            val valueHandle = charsBuf.getShort(offset + 20)

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
                        buf[1] = stats.cpuUsage.toInt().toByte()
                        buf[2] = stats.gpuTemp.toInt().toByte()
                        buf[3] = stats.gpuUsage.toInt().toByte()
                        buf[4] = stats.memUsage.toInt().toByte()
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

    /**
     * 从 CharArray 提取 null 结尾的字符串
     */
    private fun charArrayToString(chars: CharArray): String {
        val sb = StringBuilder()
        for (c in chars) {
            if (c == ' ') break
            sb.append(c)
        }
        return sb.toString().trim()
    }

    private fun scanBluetoothDevices(): List<BleDeviceInfo> {
        val devices = mutableListOf<BleDeviceInfo>()

        // 使用 Python bleak 库扫描 BLE 设备（包括未配对的广播设备）
        try {
            addLog("正在搜索附近蓝牙设备 (Python bleak)...")
            val scriptFile = java.io.File.createTempFile("ble_scan_", ".py")
            scriptFile.writeText(
                "import sys, asyncio\n" +
                "from bleak import BleakScanner\n" +
                "async def scan(timeout):\n" +
                "    devices = await BleakScanner.discover(timeout=timeout)\n" +
                "    for d in devices:\n" +
                "        name = d.name if d.name else ''\n" +
                "        addr = d.address if d.address else ''\n" +
                "        print(f'{name}|{addr}')\n" +
                "if __name__ == '__main__':\n" +
                "    t = int(sys.argv[1]) if len(sys.argv) > 1 else 10\n" +
                "    asyncio.run(scan(t))\n"
            )
            scriptFile.deleteOnExit()

            val p = Runtime.getRuntime().exec(arrayOf("python", scriptFile.absolutePath, "10"))
            val output = p.inputStream.bufferedReader(charset("UTF-8")).readText()
            p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            scriptFile.delete()

            addLog("扫描到 ${output.lines().filter { it.isNotBlank() }.size} 个 BLE 设备")

            for (line in output.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val parts = trimmed.split("|", limit = 2)
                if (parts.size >= 2) {
                    val name = parts[0].trim()
                    val address = parts[1].trim()
                    if (address.isNotEmpty()) {
                        addLog("BLE: name='$name' addr=$address")
                        devices.add(BleDeviceInfo(name, address))
                    }
                }
            }
        } catch (e: Exception) {
            addLog("BLE 扫描异常: ${e.message}")
        }

        // 回退: Get-PnpDevice 列出已配对设备
        if (devices.isEmpty()) {
            try {
                addLog("回退到 Get-PnpDevice...")
                val scriptFile = java.io.File.createTempFile("bt_pnp_", ".ps1")
                scriptFile.writeText(buildString {
                    appendLine("[Console]::OutputEncoding = [System.Text.Encoding]::UTF8")
                    appendLine("Get-PnpDevice -Class Bluetooth -ErrorAction SilentlyContinue | ForEach-Object {")
                    appendLine("  Write-Output (\$_.FriendlyName + '|' + \$_.InstanceId)")
                    appendLine("}")
                })
                scriptFile.deleteOnExit()

                val p = Runtime.getRuntime().exec(arrayOf(
                    "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", scriptFile.absolutePath
                ))
                val output = p.inputStream.bufferedReader(charset("UTF-8")).readText()
                p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
                scriptFile.delete()

                for (line in output.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    val parts = trimmed.split("|", limit = 2)
                    if (parts.size >= 2) {
                        val name = parts[0].trim()
                        val address = extractBluetoothAddress(parts[1].trim())
                        if (name.isNotEmpty()) {
                            addLog("PnP: name='$name' addr=$address")
                            devices.add(BleDeviceInfo(name, address))
                        }
                    }
                }
            } catch (e: Exception) {
                addLog("PnP 扫描异常: ${e.message}")
            }
        }

        return devices
    }

    private fun extractBluetoothAddress(instanceId: String): String {
        return try {
            val btPart = instanceId.split("\\").getOrNull(1) ?: return ""
            val addrRaw = btPart.removePrefix("Dev_").removePrefix("DEV_").take(12)
            if (addrRaw.length == 12) {
                String.format("%s:%s:%s:%s:%s:%s",
                    addrRaw.substring(0, 2), addrRaw.substring(2, 4),
                    addrRaw.substring(4, 6), addrRaw.substring(6, 8),
                    addrRaw.substring(8, 10), addrRaw.substring(10, 12))
            } else ""
        } catch (_: Exception) {
            ""
        }
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
