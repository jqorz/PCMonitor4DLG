package com.jqorz.pcmonitor4dlg.ble

import com.jqorz.pcmonitor4dlg.model.BleConnectionState
import com.jqorz.pcmonitor4dlg.model.BleDeviceInfo
import com.jqorz.pcmonitor4dlg.model.SystemStats
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Base64
import java.util.Calendar
import java.util.concurrent.TimeUnit

class BleManager {

    companion object {
        private const val LONG_VALUE_UUID = "0000ff01-0000-1000-8000-00805f9b34fb"
        private const val CTRL_POINT_UUID = "0000ff03-0000-1000-8000-00805f9b34fb"
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

    private var bleProcess: Process? = null
    private var bleWriter: java.io.BufferedWriter? = null
    private var bleReader: BufferedReader? = null

    private var longValueAttrHandle: Int = 0
    private var ctrlPointAttrHandle: Int = 0

    // ---- Minimal JSON helpers (no external dependency) ----

    /** Parse a simple JSON string value: "key": "value" */
    private fun jsonGetString(json: String, key: String): String? {
        val pattern = Regex(""""$key"\s*:\s*"([^"]*)"""")
        return pattern.find(json)?.groupValues?.get(1)
    }

    /** Parse a JSON boolean value: "key": true/false */
    private fun jsonGetBool(json: String, key: String): Boolean? {
        val pattern = Regex(""""$key"\s*:\s*(true|false)""")
        return pattern.find(json)?.groupValues?.get(1)?.toBoolean()
    }

    /** Parse devices array: [{"name":"...","address":"..."},...] */
    private fun jsonParseDevices(json: String): List<BleDeviceInfo> {
        val devices = mutableListOf<BleDeviceInfo>()
        val itemPattern = Regex("""\{[^{}]*"name"\s*:\s*"([^"]*)"[^{}]*"address"\s*:\s*"([^"]*)"[^{}]*\}""")
        for (m in itemPattern.findAll(json)) {
            val name = m.groupValues[1]
            val address = m.groupValues[2]
            if (address.isNotEmpty()) {
                devices.add(BleDeviceInfo(name, address))
            }
        }
        return devices
    }

    /** Parse chars map: {"uuid1":handle1,"uuid2":handle2,...} */
    private fun jsonParseCharsMap(json: String): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        val charsSection = Regex(""""chars"\s*:\s*\{([^}]*)\}""").find(json)?.groupValues?.get(1) ?: return result
        val entryPattern = Regex(""""([^"]+)"\s*:\s*(\d+)""")
        for (m in entryPattern.findAll(charsSection)) {
            result[m.groupValues[1]] = m.groupValues[2].toInt()
        }
        return result
    }

    // ---- Python subprocess management ----

    @Synchronized
    private fun ensureServiceRunning(): Boolean {
        val p = bleProcess
        if (p != null && p.isAlive) {
            println("[KOTLIN] ensureServiceRunning: process alive, pid=${p.pid()}")
            return true
        }
        if (p != null) {
            println("[KOTLIN] ensureServiceRunning: old process exited, exitCode=${p.exitValue()}")
        } else {
            println("[KOTLIN] ensureServiceRunning: first start")
        }

        val scriptStream = javaClass.getResourceAsStream("/ble_service.py")
        if (scriptStream == null) {
            println("[KOTLIN] ensureServiceRunning: ble_service.py not found")
            addLog("找不到 ble_service.py 资源文件")
            return false
        }
        val scriptFile = java.io.File.createTempFile("ble_service_", ".py")
        scriptFile.deleteOnExit()
        scriptFile.writeBytes(scriptStream.readBytes())
        scriptStream.close()
        println("[KOTLIN] ensureServiceRunning: script=${scriptFile.absolutePath}")

        try {
            val pb = ProcessBuilder("python", scriptFile.absolutePath)
            pb.redirectErrorStream(false)
            val process = pb.start()
            bleProcess = process
            bleWriter = process.outputStream.bufferedWriter()
            bleReader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            println("[KOTLIN] ensureServiceRunning: started, pid=${process.pid()}, alive=${process.isAlive}")

            Thread({
                try {
                    val err = process.errorStream.bufferedReader()
                    while (true) {
                        val line = err.readLine() ?: break
                        System.err.println("[ble_service] $line")
                    }
                } catch (_: Exception) {
                }
            }, "ble-stderr").apply { isDaemon = true }.start()

            addLog("BLE 服务进程已启动")
            return true
        } catch (e: Exception) {
            println("[KOTLIN] ensureServiceRunning: start failed: ${e.message}")
            addLog("启动 BLE 服务失败: ${e.message}")
            return false
        }
    }

    @Synchronized
    fun stopBleService() {
        println("[KOTLIN] stopBleService() called")
        try {
            bleWriter?.let { writer ->
                writer.write("""{"cmd":"exit"}""")
                writer.newLine()
                writer.flush()
                bleProcess?.waitFor(2, TimeUnit.SECONDS)
            }
        } catch (_: Exception) {
        }
        try {
            bleProcess?.destroyForcibly()
        } catch (_: Exception) {
        }
        bleProcess = null
        bleWriter = null
        bleReader = null
    }

    private fun sendCommandRaw(json: String): String? {
        val writer = bleWriter
        val reader = bleReader
        if (writer == null || reader == null) {
            addLog("[DEBUG] sendCommandRaw: writer或reader为null")
            return null
        }
        // Check subprocess is still alive
        val p = bleProcess
        if (p == null || !p.isAlive) {
            addLog("[DEBUG] sendCommandRaw: 子进程已退出, exitCode=${p?.exitValue()}")
            return null
        }
        try {
            addLog("[DEBUG] 发送: ${json.take(80)}")
            writer.write(json)
            writer.newLine()
            writer.flush()
            val resp = reader.readLine()
            addLog("[DEBUG] 收到: ${resp?.take(80) ?: "null (EOF)"}")
            return resp
        } catch (e: Exception) {
            addLog("[DEBUG] sendCommandRaw异常: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
    }

    // ---- Scanning ----

    fun startScan(scope: CoroutineScope) {
        stopScan()
        _connectionState.value = BleConnectionState.SCANNING
        _scannedDevices.value = emptyList()
        addLog("开始扫描蓝牙设备...")

        scanJob = scope.launch(Dispatchers.IO) {
            try {
                if (!ensureServiceRunning()) {
                    addLog("BLE 服务不可用")
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    return@launch
                }

                val startTime = System.currentTimeMillis()
                val resp = sendCommandRaw("""{"cmd":"scan","timeout":10}""")

                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed < 2000) delay(2000 - elapsed)

                if (resp == null) {
                    addLog("扫描失败: 无响应")
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    return@launch
                }

                val ok = jsonGetBool(resp, "ok") ?: false
                if (!ok) {
                    addLog("扫描失败: ${jsonGetString(resp, "error") ?: "未知错误"}")
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    return@launch
                }

                val devices = jsonParseDevices(resp)
                for (d in devices) {
                    addLog("BLE: name='${d.name}' addr=${d.address}")
                }
                _scannedDevices.value = devices
                addLog("扫描完成，找到 ${devices.size} 个设备")
            } catch (e: Exception) {
                addLog("扫描失败: ${e.message}")
            } finally {
                if (_connectionState.value == BleConnectionState.SCANNING) {
                    _connectionState.value = BleConnectionState.DISCONNECTED
                }
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

    // ---- Connection ----

    fun connect(scope: CoroutineScope, device: BleDeviceInfo) {
        println("[KOTLIN] connect() called: ${device.name} (${device.address})")
        disconnect()
        _connectionState.value = BleConnectionState.CONNECTING
        _connectedDeviceName.value = device.name
        addLog("正在连接: ${device.name} (${device.address})")

        scope.launch(Dispatchers.IO) {
            try {
                addLog("[DEBUG] 连接协程启动")
                if (!ensureServiceRunning()) {
                    addLog("BLE 服务不可用")
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    return@launch
                }
                addLog("[DEBUG] 子进程就绪，发送connect命令")

                val resp = sendCommandRaw("""{"cmd":"connect","address":"${device.address}"}""")

                if (resp == null) {
                    addLog("连接失败: 无响应")
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    return@launch
                }

                val ok = jsonGetBool(resp, "ok") ?: false
                if (!ok) {
                    addLog("连接失败: ${jsonGetString(resp, "error") ?: "未知错误"}")
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    return@launch
                }

                _connectionState.value = BleConnectionState.CONNECTED
                addLog("已连接，正在解析服务...")

                val charsMap = jsonParseCharsMap(resp)
                for ((uuid, handle) in charsMap) {
                    when (uuid.lowercase()) {
                        LONG_VALUE_UUID -> {
                            longValueAttrHandle = handle
                            addLog("找到 Long Value 特征值 (handle=$handle)")
                        }
                        CTRL_POINT_UUID -> {
                            ctrlPointAttrHandle = handle
                            addLog("找到 Control Point 特征值 (handle=$handle)")
                        }
                    }
                }

                _connectionState.value = BleConnectionState.SERVICE_READY
                addLog("服务就绪")
                addLog("[DEBUG] 子进程状态: alive=${bleProcess?.isAlive}, pid=${bleProcess?.pid()}")

            } catch (e: Exception) {
                addLog("连接异常: ${e.message}")
                _connectionState.value = BleConnectionState.DISCONNECTED
            }
        }
    }

    // ---- Time sync ----

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

    // ---- Data sync ----

    fun startDataSync(scope: CoroutineScope, statsProvider: () -> SystemStats) {
        stopDataSync()
        addLog("[DEBUG] startDataSync 启动, ctrlHandle=$ctrlPointAttrHandle")
        syncJob = scope.launch(Dispatchers.IO) {
            try {
                var writeCount = 0
                while (isActive) {
                    if (_connectionState.value == BleConnectionState.SERVICE_READY) {
                        try {
                            val stats = statsProvider()
                            val buf = ByteArray(12)
                            buf[0] = 0xB0.toByte()              // CMD_PC_MONITOR
                            buf[1] = stats.cpuUsage.toInt().toByte()   // CPU %
                            buf[2] = stats.memUsage.toInt().toByte()   // MEM %
                            buf[3] = stats.gpuTemp.toInt().toByte()    // GPU temp °C
                            buf[4] = stats.gpuUsage.toInt().toByte()   // GPU %
                            // buf[5..11] = 0, reserved
                            writeCharacteristicValue(ctrlPointAttrHandle, buf)
                            writeCount++
                            if (writeCount <= 3) addLog("[DEBUG] 第${writeCount}次写入完成, 子进程alive=${bleProcess?.isAlive}")
                        } catch (e: Exception) {
                            addLog("[DEBUG] dataSync循环异常: ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                    delay(1000)
                }
            } catch (e: Exception) {
                addLog("[DEBUG] dataSync协程崩溃: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    fun stopDataSync() {
        syncJob?.cancel()
        syncJob = null
    }

    // ---- Write ----

    private fun writeCharacteristicValue(handle: Int, data: ByteArray) {
        if (handle == 0) {
            println("[KOTLIN] writeCharacteristic: handle=0, SKIP")
            return
        }
        val dataB64 = Base64.getEncoder().encodeToString(data)
        println("[KOTLIN] writeCharacteristic: handle=$handle, dataLen=${data.size}")
        val resp = sendCommandRaw("""{"cmd":"write","handle":$handle,"data":"$dataB64"}""")
        if (resp == null) {
            println("[KOTLIN] writeCharacteristic: no response!")
        } else {
            val ok = jsonGetBool(resp, "ok") ?: false
            if (!ok) {
                val err = jsonGetString(resp, "error") ?: "unknown"
                println("[KOTLIN] writeCharacteristic: FAILED: $err")
                addLog("写入失败: $err")
            } else {
                println("[KOTLIN] writeCharacteristic: OK")
            }
        }
    }

    // ---- Disconnect ----

    fun disconnect() {
        println("[KOTLIN] disconnect() called, process alive=${bleProcess?.isAlive}")
        stopDataSync()
        if (bleProcess?.isAlive == true) {
            try {
                sendCommandRaw("""{"cmd":"disconnect_ble"}""")
            } catch (_: Exception) {
            }
        }
        longValueAttrHandle = 0
        ctrlPointAttrHandle = 0
        _connectionState.value = BleConnectionState.DISCONNECTED
        _connectedDeviceName.value = ""
        println("[KOTLIN] disconnect() done")
        addLog("设备已断开")
    }

    // ---- Logging ----

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
}
