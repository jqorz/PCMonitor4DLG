package com.jqorz.pcmonitor4dlg.monitor

import com.jqorz.pcmonitor4dlg.model.SystemStats
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import oshi.SystemInfo
import oshi.hardware.CentralProcessor
import oshi.hardware.GlobalMemory
import oshi.hardware.HardwareAbstractionLayer

class SystemMonitor {
    // OSHI 硬件对象延迟到 IO 线程初始化，避免阻塞主线程
    private lateinit var hal: HardwareAbstractionLayer
    private lateinit var processor: CentralProcessor
    private lateinit var memory: GlobalMemory
    private var oshiInitialized = false

    private val _stats = MutableStateFlow(SystemStats())
    val stats: StateFlow<SystemStats> = _stats.asStateFlow()

    private var job: Job? = null
    private var prevBytesSent = 0L
    private var prevBytesRecv = 0L
    private var prevTimestamp = 0L

    // 默认路由出口网卡缓存：只统计这一张网卡的流量，避免把 VMware/TAP/Wi-Fi 直连等
    // 虚拟网卡的本地流量也算进"网速"（它们不受宽带带宽限制，会导致读数远超实际网速）
    private var activeIfName: String? = null
    private var activeIfCheckedAt = 0L
    private lateinit var prevCpuTicks: LongArray

    // 缓存 nvidia-smi 路径
    private var nvidiaSmiPath: String? = null
    private var nvidiaSmiChecked = false

    // GPU 检测结果缓存，避免每轮都阻塞等待
    private var cachedGpuTemp = 0.0
    private var cachedGpuUsage = 0.0
    private var gpuReady = false


    /**
     * 初始化 OSHI（仅首次调用时执行，在 IO 线程中）
     */
    private fun ensureInitialized() {
        if (oshiInitialized) return
        val si = SystemInfo()
        hal = si.hardware
        processor = hal.processor
        memory = hal.memory
        prevCpuTicks = processor.systemCpuLoadTicks
        oshiInitialized = true
    }

    fun start(scope: CoroutineScope) {
        stop()

        job = scope.launch(Dispatchers.IO) {
            // 在 IO 线程中初始化 OSHI，不阻塞主线程
            ensureInitialized()
            val (initSent, initRecv) = snapshotNetTraffic()
            prevBytesSent = initSent
            prevBytesRecv = initRecv
            prevTimestamp = System.currentTimeMillis()

            // GPU 检测放到独立协程，不阻塞主循环
            val gpuJob = launch {
                detectGpuLoop()
            }

            // 主循环：立即开始采集 CPU/内存/网络，无需等待
            while (isActive) {
                val stats = collectFastStats()
                _stats.value = stats
                delay(1000)
            }
            gpuJob.cancel()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * 快速采集 CPU/内存/网络（毫秒级），立即更新 UI
     */
    private fun collectFastStats(): SystemStats {
        val currentTicks = processor.systemCpuLoadTicks
        val cpuUsage = processor.getSystemCpuLoadBetweenTicks(prevCpuTicks) * 100.0
        prevCpuTicks = currentTicks

        val totalMem = memory.total
        val availMem = memory.available
        val memUsage = if (totalMem > 0) (totalMem - availMem).toDouble() / totalMem * 100.0 else 0.0

        val (currentSent, currentRecv) = snapshotNetTraffic()
        val currentTime = System.currentTimeMillis()
        val elapsed = (currentTime - prevTimestamp).coerceAtLeast(1)

        val netUp = (currentSent - prevBytesSent) * 1000 / elapsed
        val netDown = (currentRecv - prevBytesRecv) * 1000 / elapsed

        prevBytesSent = currentSent
        prevBytesRecv = currentRecv
        prevTimestamp = currentTime

        return SystemStats(
            cpuUsage = cpuUsage.coerceIn(0.0, 100.0),
            gpuTemp = cachedGpuTemp,
            gpuUsage = cachedGpuUsage,
            memUsage = memUsage,
            netUpSpeed = netUp.coerceAtLeast(0),
            netDownSpeed = netDown.coerceAtLeast(0),
            timestamp = currentTime
        )
    }

    /**
     * GPU 异步检测循环，独立于主采集循环，完成后自动更新缓存
     */
    private suspend fun detectGpuLoop() {
        while (true) {
            try {
                updateGpuCache()
                gpuReady = true
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (_: Exception) {}
            delay(2000) // GPU 数据变化慢，2秒采一次即可
        }
    }

    // jLibreHardwareMonitor 实例（延迟初始化，用于 GPU 信息）
    private var lhmManager: Any? = null
    private var lhmFailed = false

    /**
     * 解析默认路由实际使用的网卡名（如 "eth8"）。
     * UDP connect 只在本地做路由选择、不发送任何数据包，
     * 再用出口 IP 反查所属网卡；离线（无默认路由）时返回 null。
     */
    private fun resolveActiveIfName(): String? {
        return try {
            java.net.DatagramSocket().use { socket ->
                socket.connect(java.net.InetAddress.getByName("8.8.8.8"), 53)
                val local = socket.localAddress ?: return null
                java.net.NetworkInterface.getByInetAddress(local)?.name
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 只统计默认路由网卡的累计收发字节数（sent to recv）。
     * 不再把所有网卡加总：VMware/TAP/Wi-Fi 直连等虚拟网卡的本地流量
     * 不走宽带，加总会导致显示的"网速"远超实际带宽。
     * 每 30 秒或缓存网卡从系统中消失时重新解析一次。
     */
    private fun snapshotNetTraffic(): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val nifs = hal.networkIFs
        var name = activeIfName
        if (name == null || now - activeIfCheckedAt > 30_000 || nifs.none { it.name == name }) {
            name = resolveActiveIfName()
            activeIfCheckedAt = now
            if (name != null) activeIfName = name
        }
        var sent = 0L
        var recv = 0L
        if (name != null) {
            for (nif in nifs) {
                if (nif.name == name) {
                    sent += nif.bytesSent
                    recv += nif.bytesRecv
                }
            }
        }
        return sent to recv
    }

    /**
     * 通过 jLibreHardwareManager 查询指定硬件和传感器类型
     */
    private fun queryLhmSensors(hardwareType: String, sensorType: String): List<Double> {
        if (lhmFailed) return emptyList()
        try {
            if (lhmManager == null) {
                val configClass = Class.forName("io.github.pandalxb.jlibrehardwaremonitor.config.ComputerConfig")
                val config = configClass.getMethod("getInstance").invoke(null)
                config.javaClass.getMethod("setCpuEnabled", Boolean::class.javaPrimitiveType).invoke(config, true)
                config.javaClass.getMethod("setGpuEnabled", Boolean::class.javaPrimitiveType).invoke(config, true)
                val managerClass = Class.forName("io.github.pandalxb.jlibrehardwaremonitor.manager.LibreHardwareManager")
                lhmManager = managerClass.getMethod("createInstance", configClass).invoke(null, config)
            }
            val manager = lhmManager!!
            val sensors = manager.javaClass.getMethod("querySensors", String::class.java, String::class.java)
                .invoke(manager, hardwareType, sensorType) as? List<*> ?: return emptyList()

            return sensors.mapNotNull { sensor ->
                if (sensor == null) null
                else (sensor.javaClass.getMethod("getValue").invoke(sensor) as? Number)?.toDouble()
            }.filter { it > 0 }
        } catch (e: Exception) {
            lhmFailed = true
            return emptyList()
        }
    }

    /**
     * 检测 GPU 信息（温度、负载），结果直接写入缓存字段
     * 优先尝试 nvidia-smi（快，~100ms），再尝试 jLibreHardwareMonitor（慢，1-3s）
     */
    private fun updateGpuCache() {
        // 方法1: nvidia-smi（最快，~100ms，NVIDIA GPU，同时查温度和占用率）
        if (queryNvidiaGpu()) return

        // 方法2: jLibreHardwareMonitor（需要管理员权限，首次加载慢）
        val gpuTemps = queryLhmSensors("GPU", "Temperature")
        val gpuLoads = queryLhmSensors("GPU", "Load")

        val gpuTemp = gpuTemps.firstOrNull { it in 1.0..150.0 } ?: 0.0
        val gpuUsage = gpuLoads.firstOrNull { it in 0.0..100.0 } ?: 0.0

        if (gpuTemp > 0 || gpuUsage > 0) {
            cachedGpuTemp = gpuTemp
            cachedGpuUsage = gpuUsage
            return
        }

        // 无可用 GPU 数据源
        cachedGpuTemp = 0.0
        cachedGpuUsage = 0.0
    }

    /**
     * 通过 nvidia-smi 同时获取 GPU 温度和占用率，成功返回 true
     */
    private fun queryNvidiaGpu(): Boolean {
        val smiPath = findNvidiaSmi() ?: return false
        var p: Process? = null
        try {
            p = Runtime.getRuntime().exec(arrayOf(
                smiPath,
                "--query-gpu=temperature.gpu,utilization.gpu",
                "--format=csv,noheader,nounits"
            ))
            val reader = p.inputStream.bufferedReader()
            val output = reader.readText().trim()
            reader.close()
            p.waitFor()
            val parts = output.split(",")
            if (parts.size >= 2) {
                val temp = parts[0].trim().toDoubleOrNull() ?: 0.0
                val usage = parts[1].trim().toDoubleOrNull() ?: 0.0
                if (temp > 0 || usage > 0) {
                    cachedGpuTemp = temp
                    cachedGpuUsage = usage
                    return true
                }
            }
        } catch (_: Exception) {} finally {
            p?.destroy()
        }
        return false
    }

    private fun findNvidiaSmi(): String? {
        if (nvidiaSmiChecked) return nvidiaSmiPath
        nvidiaSmiChecked = true

        val candidates = java.util.Arrays.asList(
            "nvidia-smi",
            "C:\\Windows\\System32\\nvidia-smi.exe",
            "C:\\Program Files\\NVIDIA Corporation\\NVSMI\\nvidia-smi.exe"
        )
        for (path in candidates) {
            var p: Process? = null
            try {
                p = Runtime.getRuntime().exec(arrayOf(path, "--query-gpu=temperature.gpu", "--format=csv,noheader,nounits"))
                // 消耗输出流防止进程阻塞
                java.io.BufferedReader(java.io.InputStreamReader(p.inputStream)).close()
                p.waitFor()
                if (p.exitValue() == 0) {
                    nvidiaSmiPath = path
                    return path
                }
            } catch (_: Exception) {} finally {
                p?.destroy()
            }
        }
        return null
    }
}
