package ai.mlc.mobileai

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class SystemStats(
    val cpuPercent: Float = -1f,  // -1 = first poll / measuring
    val thermalHeadroom: Float = 1f,
    val thermalAvailable: Boolean = false
)

class SystemMonitor(private val context: Context) {

    private val _stats = MutableStateFlow(SystemStats())
    val stats: StateFlow<SystemStats> = _stats

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastCpuIdle = 0L
    private var lastCpuTotal = 0L

    fun start() {
        scope.launch {
            // Prime the baseline on first read (result discarded — always 0)
            readCpuPercent()
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
        return try {
            val line = File("/proc/stat").bufferedReader().readLine() ?: return 0f
            val parts = line.trimStart().split("\\s+".toRegex())
            if (parts.size < 5 || parts[0] != "cpu") return 0f
            val user = parts[1].toLong()
            val nice = parts[2].toLong()
            val system = parts[3].toLong()
            val idle = parts[4].toLong()
            val iowait = if (parts.size > 5) parts[5].toLong() else 0L
            val irq = if (parts.size > 6) parts[6].toLong() else 0L
            val softirq = if (parts.size > 7) parts[7].toLong() else 0L

            val totalIdle = idle + iowait
            val totalCpu = user + nice + system + idle + iowait + irq + softirq

            val deltaIdle = totalIdle - lastCpuIdle
            val deltaTotal = totalCpu - lastCpuTotal

            lastCpuIdle = totalIdle
            lastCpuTotal = totalCpu

            if (deltaTotal == 0L) return 0f
            ((deltaTotal - deltaIdle).toFloat() / deltaTotal.toFloat() * 100f).coerceIn(0f, 100f)
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
