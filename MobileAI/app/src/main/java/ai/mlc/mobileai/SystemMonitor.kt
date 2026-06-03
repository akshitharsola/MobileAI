package ai.mlc.mobileai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class SystemStats(
    val cpuPercent: Float = -1f,  // -1 = measuring
    val thermalHeadroom: Float = 1f,
    val thermalAvailable: Boolean = false
)

class SystemMonitor(private val context: Context) {

    private val _stats = MutableStateFlow(SystemStats())
    val stats: StateFlow<SystemStats> = _stats

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // /proc/stat reader state
    private var lastProcIdle = 0L
    private var lastProcTotal = 0L
    private var procStatReadable = true  // false if SELinux blocks it

    // /proc/self/stat reader state (always readable — our own process)
    private var lastSelfUser = 0L
    private var lastSelfSystem = 0L
    private var lastSelfWallMs = 0L

    fun start() {
        scope.launch {
            // Prime baselines silently
            tryReadProcStat()
            readSelfCpuJiffies()
            lastSelfWallMs = System.currentTimeMillis()
            delay(2000)
            while (true) {
                _stats.value = SystemStats(
                    cpuPercent = readCpuPercent(),
                    thermalHeadroom = readThermalHeadroom(),
                    thermalAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                )
                delay(2000)
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    private fun readCpuPercent(): Float {
        // Try /proc/stat first (blocked on HyperOS by SELinux)
        if (procStatReadable) {
            val result = tryReadProcStat()
            if (result >= 0f) return result
            procStatReadable = false
        }
        // Fallback: estimate from our own process jiffies relative to wall clock.
        // Not system-wide, but reflects LLM inference load accurately.
        return readSelfCpuPercent()
    }

    private fun tryReadProcStat(): Float {
        return try {
            val line = File("/proc/stat").bufferedReader().readLine() ?: return -1f
            val parts = line.trimStart().split("\\s+".toRegex())
            if (parts.size < 5 || parts[0] != "cpu") return -1f
            val user    = parts[1].toLong()
            val nice    = parts[2].toLong()
            val system  = parts[3].toLong()
            val idle    = parts[4].toLong()
            val iowait  = if (parts.size > 5) parts[5].toLong() else 0L
            val irq     = if (parts.size > 6) parts[6].toLong() else 0L
            val softirq = if (parts.size > 7) parts[7].toLong() else 0L

            val totalIdle  = idle + iowait
            val totalCpu   = user + nice + system + idle + iowait + irq + softirq
            val deltaIdle  = totalIdle  - lastProcIdle
            val deltaTotal = totalCpu   - lastProcTotal

            lastProcIdle  = totalIdle
            lastProcTotal = totalCpu

            if (deltaTotal == 0L) return 0f
            ((deltaTotal - deltaIdle).toFloat() / deltaTotal.toFloat() * 100f).coerceIn(0f, 100f)
        } catch (e: Exception) {
            -1f
        }
    }

    // Read user+system jiffies for our own PID from /proc/self/stat
    private fun readSelfCpuJiffies(): Pair<Long, Long> {
        return try {
            val parts = File("/proc/self/stat").readText().trim().split(" ")
            // fields 13 (utime) and 14 (stime) are 0-indexed
            val utime = parts.getOrNull(13)?.toLongOrNull() ?: 0L
            val stime = parts.getOrNull(14)?.toLongOrNull() ?: 0L
            Pair(utime, stime)
        } catch (e: Exception) {
            Pair(0L, 0L)
        }
    }

    private fun readSelfCpuPercent(): Float {
        return try {
            val (utime, stime) = readSelfCpuJiffies()
            val totalJiffies = utime + stime
            val nowMs = System.currentTimeMillis()

            val deltaJiffies = totalJiffies - (lastSelfUser + lastSelfSystem)
            val deltaMs = nowMs - lastSelfWallMs

            lastSelfUser   = utime
            lastSelfSystem = stime
            lastSelfWallMs = nowMs

            if (deltaMs <= 0) return 0f
            // jiffies are in USER_HZ (100 Hz on Android), so 1 jiffy = 10ms per CPU core
            val clkTck = 100f
            val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val usedMs = deltaJiffies * (1000f / clkTck)
            val availableMs = deltaMs * cpuCores
            (usedMs / availableMs * 100f).coerceIn(0f, 100f)
        } catch (e: Exception) {
            0f
        }
    }

    private fun readThermalHeadroom(): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 1f
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.getThermalHeadroom(1).coerceIn(0f, 1f)
        } catch (e: Exception) {
            1f
        }
    }
}
