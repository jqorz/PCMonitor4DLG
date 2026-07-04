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
    private val si = SystemInfo()
    private val hal: HardwareAbstractionLayer = si.hardware
    private val processor: CentralProcessor = hal.processor
    private val memory: GlobalMemory = hal.memory

    private val _stats = MutableStateFlow(SystemStats())
    val stats: StateFlow<SystemStats> = _stats.asStateFlow()

    private var job: Job? = null
    private var prevBytesSent = 0L
    private var prevBytesRecv = 0L
    private var prevTimestamp = 0L
    private var prevCpuTicks: LongArray = processor.systemCpuLoadTicks

    // 缓存 nvidia-smi 路径
    private var nvidiaSmiPath: String? = null
    private var nvidiaSmiChecked = false

    fun start(scope: CoroutineScope) {
        stop()
        prevBytesSent = getTotalBytesSent()
        prevBytesRecv = getTotalBytesRecv()
        prevTimestamp = System.currentTimeMillis()
        prevCpuTicks = processor.systemCpuLoadTicks

        job = scope.launch(Dispatchers.IO) {
            delay(1000)
            while (isActive) {
                val stats = collectStats()
                _stats.value = stats
                delay(1000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun collectStats(): SystemStats {
        val currentTicks = processor.systemCpuLoadTicks
        val cpuUsage = processor.getSystemCpuLoadBetweenTicks(prevCpuTicks) * 100.0
        prevCpuTicks = currentTicks

        val cpuTemp = getCpuTemperature()
        val (gpuTemp, gpuUsage) = getGpuInfo()

        val totalMem = memory.total
        val availMem = memory.available
        val memUsage = if (totalMem > 0) (totalMem - availMem).toDouble() / totalMem * 100.0 else 0.0

        val currentSent = getTotalBytesSent()
        val currentRecv = getTotalBytesRecv()
        val currentTime = System.currentTimeMillis()
        val elapsed = (currentTime - prevTimestamp).coerceAtLeast(1)

        val netUp = (currentSent - prevBytesSent) * 1000 / elapsed
        val netDown = (currentRecv - prevBytesRecv) * 1000 / elapsed

        prevBytesSent = currentSent
        prevBytesRecv = currentRecv
        prevTimestamp = currentTime

        return SystemStats(
            cpuTemp = cpuTemp,
            cpuUsage = cpuUsage.coerceIn(0.0, 100.0),
            gpuTemp = gpuTemp,
            gpuUsage = gpuUsage,
            memUsage = memUsage,
            netUpSpeed = netUp.coerceAtLeast(0),
            netDownSpeed = netDown.coerceAtLeast(0),
            timestamp = currentTime
        )
    }

    // jLibreHardwareMonitor 实例（延迟初始化）
    private var lhmManager: Any? = null
    private var lhmFailed = false

    /**
     * 通过 jLibreHardwareMonitor 获取 CPU 温度
     * 注意：需要管理员权限，否则返回 0
     */
    private fun getCpuTemperature(): Double {
        // 方法1: jLibreHardwareMonitor（需要管理员权限）
        if (!lhmFailed) {
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
                val querySensors = manager.javaClass.getMethod("querySensors", String::class.java, String::class.java)

                // 查询 CPU 温度
                val cpuSensors = querySensors.invoke(manager, "CPU", "Temperature") as? List<*>
                if (cpuSensors != null) {
                    for (sensor in cpuSensors) {
                        if (sensor == null) continue
                        val value = sensor.javaClass.getMethod("getValue").invoke(sensor) as? Number
                        val temp = value?.toDouble() ?: 0.0
                        if (temp > 0 && temp < 150) return temp
                    }
                }
            } catch (e: Exception) {
                lhmFailed = true
            }
        }

        // 方法2: OSHI sensors（回退，也需要管理员权限）
        val oshiTemp = hal.sensors.cpuTemperature
        if (oshiTemp > 0 && oshiTemp < 150) return oshiTemp

        return 0.0
    }

    private fun getTotalBytesSent(): Long = hal.networkIFs.sumOf { it.bytesSent }
    private fun getTotalBytesRecv(): Long = hal.networkIFs.sumOf { it.bytesRecv }

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
     * 获取 GPU 信息（温度、负载）
     */
    private fun getGpuInfo(): Pair<Double, Double> {
        // 方法1: jLibreHardwareMonitor（需要管理员权限，最准确）
        val gpuTemps = queryLhmSensors("GPU", "Temperature")
        val gpuLoads = queryLhmSensors("GPU", "Load")

        val gpuTemp = gpuTemps.firstOrNull { it in 1.0..150.0 } ?: 0.0
        val gpuUsage = gpuLoads.firstOrNull { it in 0.0..100.0 } ?: 0.0

        if (gpuTemp > 0 || gpuUsage > 0) return Pair(gpuTemp, gpuUsage)

        // 方法2: nvidia-smi（NVIDIA GPU）
        val nvidiaTemp = getNvidiaGpuTemp()
        if (nvidiaTemp > 0) return Pair(nvidiaTemp, 0.0)

        // 方法3: typeperf GPU Engine 占用率
        val typeperfUsage = getTypeperfGpuUsage()
        return Pair(0.0, typeperfUsage)
    }

    private fun getTypeperfGpuUsage(): Double {
        try {
            val p = Runtime.getRuntime().exec(arrayOf(
                "cmd", "/c",
                "typeperf \"\\GPU Engine(*)\\Utilization Percentage\" -sc 1 -y"
            ))
            val output = p.inputStream.bufferedReader().readText()
            p.waitFor()
            val lines = output.lines().filter { it.contains(",") && !it.startsWith("\"PDH") }
            if (lines.size >= 2) {
                val dataLine = lines.last()
                val parts = dataLine.split(",").drop(2)
                var total = 0.0
                for (part in parts) {
                    total += part.trim().trim('"').toDoubleOrNull() ?: 0.0
                }
                return total.coerceIn(0.0, 100.0)
            }
        } catch (_: Exception) {}
        return 0.0
    }

    /**
     * 通过 nvidia-smi 获取 NVIDIA GPU 温度
     */
    private fun getNvidiaGpuTemp(): Double {
        val smiPath = findNvidiaSmi() ?: return 0.0
        try {
            val p = Runtime.getRuntime().exec(arrayOf(smiPath, "--query-gpu=temperature.gpu", "--format=csv,noheader,nounits"))
            val output = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            return output.toDoubleOrNull() ?: 0.0
        } catch (_: Exception) {}
        return 0.0
    }

    private fun findNvidiaSmi(): String? {
        if (nvidiaSmiChecked) return nvidiaSmiPath
        nvidiaSmiChecked = true

        // 常见路径
        val candidates = listOf(
            "nvidia-smi",
            "C:\\Windows\\System32\\nvidia-smi.exe",
            "C:\\Program Files\\NVIDIA Corporation\\NVSMI\\nvidia-smi.exe"
        )
        for (path in candidates) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf(path, "--query-gpu=temperature.gpu", "--format=csv,noheader,nounits"))
                p.waitFor()
                if (p.exitValue() == 0) {
                    nvidiaSmiPath = path
                    return path
                }
            } catch (_: Exception) {}
        }
        return null
    }
}
