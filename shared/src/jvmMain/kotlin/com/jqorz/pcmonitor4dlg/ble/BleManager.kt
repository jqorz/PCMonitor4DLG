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

    // ---- Minimal JSON helpers ----

    private fun jsonGetString(json: String, key: String): String? {
        val pattern = Regex(""""$key"\s*:\s*"([^"]*)"""")
        return pattern.find(json)?.groupValues?.get(1)
    }

    private fun jsonGetBool(json: String, key: String): Boolean? {
        val pattern = Regex(""""$key"\s*:\s*(true|false)""")
        return pattern.find(json)?.groupValues?.get(1)?.toBoolean()
    }

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
                        val buf = ByteArray(12)
                        buf[0] = 0xB0.toByte()
                        buf[1] = stats.cpuUsage.toInt().toByte()
                        buf[2] = stats.memUsage.toInt().toByte()
                        buf[3] = stats.gpuTemp.toInt().toByte()
                        buf[4] = stats.gpuUsage.toInt().toByte()
                        writeCharacteristic(ctrlPointAttrHandle, buf)
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
        val dataB64 = Base64.getEncoder().encodeToString(data)
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
        val now = Calendar.getInstance()
        val timestamp = String.format("%02d:%02d:%02d",
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            now.get(Calendar.SECOND))
        _logs.value = _logs.value + "[$timestamp] $msg"
    }
}
