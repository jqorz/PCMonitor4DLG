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
        private const val MAX_LOG_ENTRIES = 200

        // 预编译 Regex，避免每次调用重新编译
        private val REGEX_JSON_STRING = Regex(""""(\w+)"\s*:\s*"([^"]*)"""")
        private val REGEX_JSON_BOOL = Regex(""""(\w+)"\s*:\s*(true|false)""")
        private val REGEX_DEVICE_ITEM = Regex("""\{[^{}]*"name"\s*:\s*"([^"]*)"[^{}]*"address"\s*:\s*"([^"]*)"[^{}]*\}""")
        private val REGEX_CHARS_SECTION = Regex(""""chars"\s*:\s*\{([^}]*)\}""")
        private val REGEX_CHARS_ENTRY = Regex(""""([^"]+)"\s*:\s*(\d+)""")

        // 缓存 Base64 编码器
        private val BASE64_ENCODER = java.util.Base64.getEncoder()
    }

    /** 日志开关，默认关闭以减少内存分配，需要调试时设为 true */
    var logEnabled = false

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow("")
    val connectedDeviceName: StateFlow<String> = _connectedDeviceName.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<BleDeviceInfo>>(emptyList())
    val scannedDevices: StateFlow<List<BleDeviceInfo>> = _scannedDevices.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    // 复用的写缓冲区，避免每秒分配 ByteArray
    private val writeBuffer = ByteArray(12)

    private var scanJob: Job? = null
    private var syncJob: Job? = null

    private var bleProcess: Process? = null
    private var bleWriter: java.io.BufferedWriter? = null
    private var bleReader: BufferedReader? = null

    private var longValueAttrHandle: Int = 0
    private var ctrlPointAttrHandle: Int = 0

    // ---- Minimal JSON helpers ----

    private fun jsonGetString(json: String, key: String): String? {
        // 使用预编译 Regex，在整个 json 中查找 key 对应的值
        for (m in REGEX_JSON_STRING.findAll(json)) {
            if (m.groupValues[1] == key) return m.groupValues[2]
        }
        return null
    }

    private fun jsonGetBool(json: String, key: String): Boolean? {
        for (m in REGEX_JSON_BOOL.findAll(json)) {
            if (m.groupValues[1] == key) return m.groupValues[2].toBoolean()
        }
        return null
    }

    private fun jsonParseDevices(json: String): List<BleDeviceInfo> {
        val devices = mutableListOf<BleDeviceInfo>()
        for (m in REGEX_DEVICE_ITEM.findAll(json)) {
            val name = m.groupValues[1]
            val address = m.groupValues[2]
            if (address.isNotEmpty()) {
                devices.add(BleDeviceInfo(name, address))
            }
        }
        return devices
    }

    private fun jsonParseCharsMap(json: String): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        val charsSection = REGEX_CHARS_SECTION.find(json)?.groupValues?.get(1) ?: return result
        for (m in REGEX_CHARS_ENTRY.findAll(charsSection)) {
            result[m.groupValues[1]] = m.groupValues[2].toInt()
        }
        return result
    }

    // ---- Python subprocess management ----

    @Synchronized
    private fun ensureServiceRunning(): Boolean {
        val p = bleProcess
        if (p != null && p.isAlive) return true

        val scriptStream = javaClass.getResourceAsStream("/ble_service.py")
        if (scriptStream == null) {
            addLog("ERROR: ble_service.py not found in resources")
            return false
        }
        val scriptFile = java.io.File.createTempFile("ble_service_", ".py")
        scriptFile.deleteOnExit()
        scriptFile.writeBytes(scriptStream.readBytes())
        scriptStream.close()

        try {
            val pb = ProcessBuilder("python", scriptFile.absolutePath)
            pb.redirectErrorStream(false)
            val process = pb.start()
            bleProcess = process
            bleWriter = process.outputStream.bufferedWriter()
            bleReader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))

            // Drain stderr in background, detect disconnect events
            Thread({
                try {
                    val err = process.errorStream.bufferedReader()
                    while (true) {
                        val line = err.readLine() ?: break
                        if (line.startsWith("[BLE_EVENT]")) {
                            // Device disconnected unexpectedly
                            addLog("BLE device disconnected unexpectedly")
                            longValueAttrHandle = 0
                            ctrlPointAttrHandle = 0
                            _connectionState.value = BleConnectionState.DISCONNECTED
                            _connectedDeviceName.value = ""
                        } else {
                            System.err.println("[ble] $line")
                        }
                    }
                } catch (_: Exception) {
                }
            }, "ble-stderr").apply { isDaemon = true }.start()

            addLog("BLE subprocess started, pid=${process.pid()}")
            return true
        } catch (e: Exception) {
            addLog("Failed to start BLE subprocess: ${e.message}")
            return false
        }
    }

    @Synchronized
    fun stopBleService() {
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

    private fun sendCommand(json: String): String? {
        val writer = bleWriter ?: return null
        val reader = bleReader ?: return null
        val p = bleProcess ?: return null
        if (!p.isAlive) return null
        try {
            writer.write(json)
            writer.newLine()
            writer.flush()
            return reader.readLine()
        } catch (e: Exception) {
            addLog("sendCommand error: ${e.message}")
            return null
        }
    }

    // ---- Scanning ----

    fun startScan(scope: CoroutineScope) {
        stopScan()
        _connectionState.value = BleConnectionState.SCANNING
        _scannedDevices.value = emptyList()

        scanJob = scope.launch(Dispatchers.IO) {
            try {
                if (!ensureServiceRunning()) {
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    return@launch
                }

                val startTime = System.currentTimeMillis()
                val resp = sendCommand("""{"cmd":"scan","timeout":10}""")

                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed < 2000) delay(2000 - elapsed)

                if (resp == null) {
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    return@launch
                }

                val ok = jsonGetBool(resp, "ok") ?: false
                if (!ok) {
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    return@launch
                }

                _scannedDevices.value = jsonParseDevices(resp)
            } catch (_: Exception) {
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
        disconnect()
        _connectionState.value = BleConnectionState.CONNECTING
        _connectedDeviceName.value = device.name

        scope.launch(Dispatchers.IO) {
            try {
                if (!ensureServiceRunning()) {
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    return@launch
                }

                val resp = sendCommand("""{"cmd":"connect","address":"${device.address}"}""")

                if (resp == null) {
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    return@launch
                }

                val ok = jsonGetBool(resp, "ok") ?: false
                if (!ok) {
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    return@launch
                }

                _connectionState.value = BleConnectionState.CONNECTED

                val charsMap = jsonParseCharsMap(resp)
                for ((uuid, handle) in charsMap) {
                    when (uuid.lowercase()) {
                        LONG_VALUE_UUID -> longValueAttrHandle = handle
                        CTRL_POINT_UUID -> ctrlPointAttrHandle = handle
                    }
                }

                _connectionState.value = BleConnectionState.SERVICE_READY
            } catch (_: Exception) {
                _connectionState.value = BleConnectionState.DISCONNECTED
            }
        }
    }

    // ---- Data sync ----

    fun startDataSync(scope: CoroutineScope, statsProvider: () -> SystemStats) {
        stopDataSync()
        syncJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (_connectionState.value == BleConnectionState.SERVICE_READY) {
                    try {
                        val stats = statsProvider()
                        // 文档协议: Control Point (0xFF03), 命令字 0xB0, 12字节
                        // 复用 writeBuffer，避免每秒分配新 ByteArray
                        writeBuffer[0] = 0xB0.toByte()
                        writeBuffer[1] = stats.cpuUsage.toInt().toByte()
                        writeBuffer[2] = stats.memUsage.toInt().toByte()
                        writeBuffer[3] = stats.gpuTemp.toInt().toByte()
                        writeBuffer[4] = stats.gpuUsage.toInt().toByte()
                        writeCharacteristic(ctrlPointAttrHandle, writeBuffer)
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

    // ---- Write ----

    private fun writeCharacteristic(handle: Int, data: ByteArray) {
        if (handle == 0) return
        val dataB64 = BASE64_ENCODER.encodeToString(data)
        sendCommand("""{"cmd":"write","handle":$handle,"data":"$dataB64"}""")
    }

    // ---- Disconnect ----

    fun disconnect() {
        stopDataSync()
        if (bleProcess?.isAlive == true) {
            try {
                sendCommand("""{"cmd":"disconnect_ble"}""")
            } catch (_: Exception) {
            }
        }
        longValueAttrHandle = 0
        ctrlPointAttrHandle = 0
        _connectionState.value = BleConnectionState.DISCONNECTED
        _connectedDeviceName.value = ""
    }

    // ---- Logging ----

    fun clearLog() {
        _logs.value = emptyList()
    }

    private fun addLog(msg: String) {
        if (!logEnabled) return
        val now = Calendar.getInstance()
        val timestamp = String.format("%02d:%02d:%02d",
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            now.get(Calendar.SECOND))
        val newEntry = "[$timestamp] $msg"
        val updated = java.util.ArrayList(_logs.value)
        // 限制日志条数，超出时丢弃最旧的
        if (updated.size >= MAX_LOG_ENTRIES) {
            updated.subList(0, updated.size - MAX_LOG_ENTRIES + 1).clear()
        }
        updated.add(newEntry)
        _logs.value = updated
    }
}
