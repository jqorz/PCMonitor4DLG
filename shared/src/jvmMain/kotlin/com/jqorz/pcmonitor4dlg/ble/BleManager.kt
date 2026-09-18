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

        // OTA：固件bin内的版本魔数（epd_version[0..1] = 0xF9A51379 + 取反），其后第8字节起为版本号LE16
        private val FIRMWARE_MAGIC = byteArrayOf(0x79, 0x13, 0xA5.toByte(), 0xF9.toByte(), 0x86.toByte(), 0xEC.toByte(), 0x5A, 0x06)
        // OTA镜像槽容量：0xC000(48KB) - 64字节镜像头
        private const val OTA_MIN_SIZE = 1024
        private const val OTA_MAX_SIZE = 49088
        // 每包写入失败重试次数（Python端write为有响应写，返回{"ok":true}即设备已确认）
        private const val OTA_WRITE_RETRIES = 50

        // 预编译 Regex，避免每次调用重新编译
        private val REGEX_JSON_STRING = Regex(""""(\w+)"\s*:\s*"([^"]*)"""")
        private val REGEX_JSON_BOOL = Regex(""""(\w+)"\s*:\s*(true|false)""")
        private val REGEX_DEVICE_ITEM = Regex("""\{[^{}]*"name"\s*:\s*"([^"]*)"[^{}]*"address"\s*:\s*"([^"]*)"(?:[^{}]*"version"\s*:\s*(\d+))?[^{}]*\}""")
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

    /** 已连接设备的固件版本号（来自扫描广播或持久化记录），0 表示未知 */
    private val _connectedDeviceVersion = MutableStateFlow(0)
    val connectedDeviceVersion: StateFlow<Int> = _connectedDeviceVersion.asStateFlow()

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

    /** OTA升级进行中标志：暂停0xB0数据同步、阻止自动重连抢占总线 */
    @Volatile
    private var otaActive = false
    val isOtaActive: Boolean get() = otaActive

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
                // 固件在广播的厂商AD里带了版本号（0 表示未携带）
                val version = m.groupValues[3].toIntOrNull() ?: 0
                devices.add(BleDeviceInfo(name, address, version))
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

        // 优先使用打包的 ble_service.exe（PyInstaller 产物），目标机器无需安装 Python 和 bleak
        val exeStream = javaClass.getResourceAsStream("/ble_service.exe")
        if (exeStream != null) {
            try {
                val exeFile = java.io.File.createTempFile("ble_service_", ".exe")
                exeFile.deleteOnExit()
                exeFile.writeBytes(exeStream.readBytes())
                if (startBleProcess(listOf(exeFile.absolutePath))) return true
            } catch (e: Exception) {
                addLog("Failed to start bundled ble_service.exe: ${e.message}")
            } finally {
                try {
                    exeStream.close()
                } catch (_: Exception) {
                }
            }
            // exe 启动失败时继续尝试 python 回退
        }

        // 开发模式回退：用系统 python 运行 ble_service.py（需要 pip install bleak）
        val scriptStream = javaClass.getResourceAsStream("/ble_service.py")
        if (scriptStream == null) {
            addLog("ERROR: ble_service.exe / ble_service.py not found in resources")
            return false
        }
        val scriptFile = java.io.File.createTempFile("ble_service_", ".py")
        scriptFile.deleteOnExit()
        scriptFile.writeBytes(scriptStream.readBytes())
        scriptStream.close()

        return startBleProcess(listOf("python", scriptFile.absolutePath))
    }

    private fun startBleProcess(command: List<String>): Boolean {
        return try {
            val pb = ProcessBuilder(command)
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
                            _connectedDeviceVersion.value = 0
                        } else {
                            System.err.println("[ble] $line")
                        }
                    }
                } catch (_: Exception) {
                }
            }, "ble-stderr").apply { isDaemon = true }.start()

            addLog("BLE subprocess started: $command, pid=${process.pid()}")
            true
        } catch (e: Exception) {
            addLog("Failed to start BLE subprocess: ${e.message}")
            false
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

    // 子进程命令协议是严格串行的，写入必须互斥，防止多线程写行交错损坏
    @Synchronized
    private fun sendLine(json: String): Boolean {
        val writer = bleWriter ?: return false
        try {
            writer.write(json)
            writer.newLine()
            writer.flush()
            return true
        } catch (e: Exception) {
            addLog("sendCommand error: ${e.message}")
            return false
        }
    }

    private fun readResponse(): String? {
        val reader = bleReader ?: return null
        val p = bleProcess ?: return null
        if (!p.isAlive) return null
        return try {
            reader.readLine()
        } catch (e: Exception) {
            addLog("readResponse error: ${e.message}")
            null
        }
    }

    private fun sendCommand(json: String): String? {
        if (!sendLine(json)) return null
        return readResponse()
    }

    // ---- Scanning ----

    fun startScan(scope: CoroutineScope) {
        stopScanAndWait() // 终止并排空上一次扫描，保证本扫描独占响应管道
        _connectionState.value = BleConnectionState.SCANNING
        _scannedDevices.value = emptyList()

        scanJob = scope.launch(Dispatchers.IO) {
            try {
                if (!ensureServiceRunning()) {
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    return@launch
                }

                if (!sendLine("""{"cmd":"scan","timeout":10}""")) {
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    return@launch
                }

                // 边扫描边接收：子进程扫描期间持续上报 partial 响应，
                // 每收到一行就刷新设备列表，直到不带 partial 标记的最终响应
                while (true) {
                    val resp = readResponse() ?: break
                    val ok = jsonGetBool(resp, "ok") ?: false
                    if (!ok) break
                    if (isActive) {
                        // 停止扫描后仍继续读完剩余响应（避免残留行错位），但不再刷新UI
                        _scannedDevices.value = jsonParseDevices(resp)
                    }
                    if (jsonGetBool(resp, "partial") != true) break
                }
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
        // 终止子进程侧扫描（无响应命令）：py收到后立刻取消扫描并发出扫描final响应，
        // 由排空协程消费后退出，命令管道恢复干净。幂等，无扫描时是空操作
        if (bleProcess?.isAlive == true) {
            sendLine("""{"cmd":"stop_scan"}""")
        }
    }

    /**
     * 终止扫描并等待排空协程退出（它读到扫描final响应后自然结束）。
     *
     * 子进程的扫描响应（partial流+final）由scanJob独占消费；发起连接或新扫描前必须
     * 等它退出，否则两个读者竞争抢行会造成命令响应错位——表面连接成功但特征句柄
     * 解析为0，数据同步静默停止。stop_scan后py立刻结束扫描，通常<100ms返回。
     */
    private fun stopScanAndWait(timeoutMs: Long = 3000) {
        val job = scanJob
        stopScan()
        if (job != null && job.isActive) {
            runBlocking { withTimeoutOrNull(timeoutMs) { job.join() } }
        }
    }

    // ---- Connection ----

    fun connect(scope: CoroutineScope, device: BleDeviceInfo) {
        stopScanAndWait() // 排空扫描响应流，避免连接命令的响应被错位消费
        disconnect()
        _connectionState.value = BleConnectionState.CONNECTING
        _connectedDeviceName.value = device.name
        _connectedDeviceVersion.value = device.version

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

                // 响应错位保护：连接ok却没解析到目标特征，说明响应流已错乱，
                // 按失败处理。否则句柄为0会让数据同步静默停止（表面连接正常但设备永不刷新）
                if (ctrlPointAttrHandle == 0 && longValueAttrHandle == 0) {
                    addLog("connect ok but no chars found (desync?): ${resp.take(120)}")
                    System.err.println("[ble] connect ok but no chars found: ${resp.take(200)}")
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    return@launch
                }

                _connectionState.value = BleConnectionState.SERVICE_READY
            } catch (_: Exception) {
                _connectionState.value = BleConnectionState.DISCONNECTED
            }
        }
    }

    /**
     * 通过 MAC 地址直接连接设备，用于自动重连场景。
     * 连接成功后设备名称会从响应中获取；[version] 为持久化的固件版本号，0 表示未知。
     */
    fun connectByAddress(scope: CoroutineScope, address: String, version: Int = 0) {
        stopScanAndWait() // 排空扫描响应流，避免连接命令的响应被错位消费
        disconnect()
        _connectionState.value = BleConnectionState.CONNECTING
        _connectedDeviceName.value = address
        _connectedDeviceVersion.value = version

        scope.launch(Dispatchers.IO) {
            try {
                if (!ensureServiceRunning()) {
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    return@launch
                }

                val resp = sendCommand("""{"cmd":"connect","address":"$address"}""")

                if (resp == null) {
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    return@launch
                }

                val ok = jsonGetBool(resp, "ok") ?: false
                if (!ok) {
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    return@launch
                }

                // 尝试从响应中获取设备名称
                val devName = jsonGetString(resp, "name")
                if (!devName.isNullOrEmpty()) {
                    _connectedDeviceName.value = devName
                }

                _connectionState.value = BleConnectionState.CONNECTED

                val charsMap = jsonParseCharsMap(resp)
                for ((uuid, handle) in charsMap) {
                    when (uuid.lowercase()) {
                        LONG_VALUE_UUID -> longValueAttrHandle = handle
                        CTRL_POINT_UUID -> ctrlPointAttrHandle = handle
                    }
                }

                // 响应错位保护：同connect()
                if (ctrlPointAttrHandle == 0 && longValueAttrHandle == 0) {
                    addLog("connect ok but no chars found (desync?): ${resp.take(120)}")
                    System.err.println("[ble] connect ok but no chars found: ${resp.take(200)}")
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    return@launch
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
                if (_connectionState.value == BleConnectionState.SERVICE_READY && !otaActive) {
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

    // ---- OTA firmware upgrade ----

    /**
     * 校验OTA固件文件，返回固件版本号。
     *
     * 校验项：文件大小(1024~49088字节，即镜像槽容量) + 版本魔数。
     * 失败抛 [IllegalArgumentException]，message 为可直接展示的中文原因。
     */
    fun validateFirmware(path: String): Int {
        val firmBuf = java.io.File(path).readBytes()
        if (firmBuf.size < OTA_MIN_SIZE || firmBuf.size > OTA_MAX_SIZE) {
            throw IllegalArgumentException(
                "文件大小 ${firmBuf.size} 字节，超出OTA范围($OTA_MIN_SIZE~$OTA_MAX_SIZE)。\n" +
                "请选择 tft_monitor_ota_*.bin（RAM运行版），不要选整刷镜像 fw_multi_part_spi.bin"
            )
        }
        val pos = findPattern(firmBuf, FIRMWARE_MAGIC)
            ?: throw IllegalArgumentException("无效固件文件：未找到版本魔数")
        return (firmBuf[pos + 8].toInt() and 0xFF) or ((firmBuf[pos + 9].toInt() and 0xFF) shl 8)
    }

    /**
     * 通过 Long Value (0xFF01) 执行OTA升级。
     *
     * 协议（与DlgClockBle App一致）：
     * - 0xA0起始(固件大小LE16) → 0xA2/0xA3交替传输(每包136字节，数据在buf[8..135]，0xA3提交256字节flash页)
     * - 首包0xA2携带64字节镜像头(魔数0x00AA5170/大小/CRC32/版本0xA50F0000+v)
     * - 0xA4结束。新固件(v9+)收到最后一页后自行断开重启，0xA4写失败属正常
     *
     * 传输期间暂停0xB0数据同步([otaActive])；中途失败会主动断开以清除设备端升级状态。
     *
     * @param onProgress 进度回调(0..100)
     * @param onFinished 结束回调(success + 中文结果信息)
     */
    fun startOta(
        scope: CoroutineScope,
        filePath: String,
        onProgress: (Int) -> Unit = {},
        onFinished: (success: Boolean, message: String) -> Unit
    ) {
        val handle = longValueAttrHandle
        if (handle == 0 || _connectionState.value != BleConnectionState.SERVICE_READY) {
            onFinished(false, "设备未连接，无法升级")
            return
        }

        stopDataSync() // 暂停0xB0数据流，避免与OTA包在子进程FIFO上交错

        scope.launch(Dispatchers.IO) {
            otaActive = true
            try {
                val firmBuf = java.io.File(filePath).readBytes()
                val firmSize = firmBuf.size
                if (firmSize < OTA_MIN_SIZE || firmSize > OTA_MAX_SIZE) {
                    throw IllegalStateException("固件大小 $firmSize 字节超出OTA范围")
                }
                val magicPos = findPattern(firmBuf, FIRMWARE_MAGIC)
                    ?: throw IllegalStateException("固件内未找到版本魔数")
                val firmVer = (firmBuf[magicPos + 8].toInt() and 0xFF) or
                        ((firmBuf[magicPos + 9].toInt() and 0xFF) shl 8)
                val crc = java.util.zip.CRC32().apply { update(firmBuf, 0, firmSize) }.value.toInt()

                addLog("OTA start: v$firmVer, $firmSize bytes, crc=${Integer.toHexString(crc)}")
                onProgress(0)

                // 升级镜像 = 64字节镜像头 + 固件，按128字节分片成136字节定长包
                val totalSize = firmSize + 64
                val chunkCount = (totalSize + 127) / 128
                val buf = ByteArray(136)

                // 0xA0 起始包
                buf.fill(0)
                buf[0] = 0xA0.toByte()
                buf[2] = (firmSize and 0xFF).toByte()
                buf[3] = ((firmSize shr 8) and 0xFF).toByte()
                if (!writeOtaPacket(handle, buf)) {
                    throw IllegalStateException("发送起始命令失败(设备可能已断开)")
                }
                delay(200)

                // 数据包：0xA2页前半 / 0xA3页后半（设备收到0xA3才写flash页）
                for (n in 0 until chunkCount) {
                    buf.fill(0xFF.toByte())
                    buf[0] = (if (n % 2 == 0) 0xA2 else 0xA3).toByte()
                    if (n == 0) {
                        // 首包带镜像头：+0x00魔数 +0x04大小 +0x08 CRC32 +0x1C版本 +0x40起固件数据
                        putLe32(buf, 8, 0x00AA5170)
                        putLe32(buf, 12, firmSize)
                        putLe32(buf, 16, crc)
                        putLe32(buf, 36, -0x5AF10000 + firmVer) // = 0xA50F0000 + 版本号
                        buf[40] = 0
                        System.arraycopy(firmBuf, 0, buf, 72, 64)
                    } else {
                        val fwOff = n * 128 - 64 // 本分片在固件中的起始偏移
                        val len = minOf(128, firmSize - fwOff)
                        if (len > 0) {
                            System.arraycopy(firmBuf, fwOff, buf, 8, len)
                        }
                    }
                    if (!writeOtaPacket(handle, buf)) {
                        throw IllegalStateException("第 ${n + 1}/$chunkCount 包写入失败(设备可能已断开)")
                    }
                    onProgress(minOf(100, (n + 1) * 128 * 100 / totalSize))
                    delay(30)
                }

                // 尾包冲刷：最后一包若是0xA2(半页)，补一包0xFF填充的0xA3提交最后半页
                if ((chunkCount - 1) % 2 == 0) {
                    buf.fill(0xFF.toByte())
                    buf[0] = 0xA3.toByte()
                    writeOtaPacket(handle, buf)
                }

                // 0xA4 结束包：新固件此时已自行重启断开，写失败属正常，不校验结果
                buf.fill(0xFF.toByte())
                buf[0] = 0xA4.toByte()
                buf[1] = 0; buf[2] = 0; buf[3] = 0
                writeOtaPacket(handle, buf)

                addLog("OTA done: v$firmVer transferred")
                _connectedDeviceVersion.value = firmVer // 设备即将重启，界面先更新为新版本
                onProgress(100)
                onFinished(true, "升级完成 v$firmVer：设备已重启（连接断开属正常），请重新连接")
            } catch (e: Exception) {
                addLog("OTA failed: ${e.message}")
                // 主动断开：清除设备端ota_state（否则界面永久冻结），设备恢复运行后可重试
                try {
                    disconnect()
                } catch (_: Exception) {
                }
                onFinished(false, "升级失败：${e.message}")
            } finally {
                otaActive = false
            }
        }
    }

    /**
     * 发送一个OTA包（有响应写），校验子进程返回的{"ok":true}。
     *
     * 失败时重试：Android实测依赖系统排队会静默丢包(尤其最后的0xA4)，
     * 必须逐包等设备确认。连接断开(handle被清/状态变化/进程死亡)时立即失败。
     */
    private fun writeOtaPacket(handle: Int, data: ByteArray): Boolean {
        val dataB64 = BASE64_ENCODER.encodeToString(data)
        val json = """{"cmd":"write","handle":$handle,"data":"$dataB64"}"""
        repeat(OTA_WRITE_RETRIES) {
            if (handle != longValueAttrHandle) return false // 连接已断开
            if (_connectionState.value != BleConnectionState.SERVICE_READY) return false
            val resp = sendCommand(json) ?: return false // 子进程已死
            if (jsonGetBool(resp, "ok") == true) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun findPattern(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun putLe32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
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
        _connectedDeviceVersion.value = 0
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
