package ai.mlc.mobileai

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

data class SystemStats(
    val cpuPercent: Float = -1f,  // -1 = measuring
    val thermalHeadroom: Float = 1f,
    val thermalAvailable: Boolean = false
)

enum class ThermalState { COOL, WARM, HOT, CRITICAL }

data class ThermalInfo(
    val state: ThermalState = ThermalState.COOL,
    val headroom1s: Float = 1f,
    val headroom10s: Float = 1f,
    val source: String = "API",  // "API" or "Battery"
    val softLimit: Boolean = false,
    val hardLimit: Boolean = false
)

class ThermalGovernor(private val context: Context) {

    @Volatile var softLimit = false
    @Volatile var hardLimit = false

    private val _state = MutableStateFlow(ThermalInfo())
    val state: StateFlow<ThermalInfo> = _state

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val listenerExecutor: Executor = Executors.newSingleThreadExecutor()

    // Track consecutive NaN/zero readings to detect HyperOS spoofing
    private var spoofCount = 0
    private var batteryTempC = 25f

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250)
            batteryTempC = rawTemp / 10f
        }
    }

    fun start() {
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            registerThermalListener()
        }

        scope.launch {
            while (true) {
                updateThermal()
                delay(1000)
            }
        }
    }

    fun stop() {
        try { context.unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            unregisterThermalListener()
        }
        scope.cancel()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun registerThermalListener() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.addThermalStatusListener(listenerExecutor) { status ->
                val newSoft = status >= PowerManager.THERMAL_STATUS_MODERATE
                val newHard = status >= PowerManager.THERMAL_STATUS_SEVERE
                softLimit = newSoft
                hardLimit = newHard
                // Trigger immediate state refresh
                scope.launch { updateThermal() }
            }
        } catch (_: Exception) {}
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun unregisterThermalListener() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.removeThermalStatusListener { }
        } catch (_: Exception) {}
    }

    private fun updateThermal() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        val h1 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { pm.getThermalHeadroom(1) } catch (_: Exception) { Float.NaN }
        } else Float.NaN

        val h10 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { pm.getThermalHeadroom(10) } catch (_: Exception) { Float.NaN }
        } else Float.NaN

        // Detect HyperOS spoofing: NaN or 0.0 for >10 consecutive readings
        val apiSpoofed = (h1.isNaN() || h1 == 0f) && (h10.isNaN() || h10 == 0f)
        if (apiSpoofed) spoofCount++ else spoofCount = 0

        val useBatteryFallback = apiSpoofed && spoofCount > 10

        val (soft, hard, state, src) = if (useBatteryFallback) {
            val s = batteryTempC > 40f
            val h = batteryTempC > 43f
            val st = when {
                batteryTempC > 43f -> ThermalState.CRITICAL
                batteryTempC > 40f -> ThermalState.HOT
                batteryTempC > 37f -> ThermalState.WARM
                else               -> ThermalState.COOL
            }
            listOf(s, h, st, "Battery")
        } else {
            val eff1  = if (h1.isNaN())  1f else h1.coerceIn(0f, 1f)
            val eff10 = if (h10.isNaN()) 1f else h10.coerceIn(0f, 1f)
            val s = eff10 < 0.4f || eff1 < 0.3f
            val h = eff1 < 0.2f
            val st = when {
                eff1 < 0.2f  -> ThermalState.CRITICAL
                eff1 < 0.35f -> ThermalState.HOT
                eff10 < 0.4f -> ThermalState.WARM
                else         -> ThermalState.COOL
            }
            listOf(s, h, st, "API")
        }

        softLimit = soft as Boolean
        hardLimit = hard as Boolean

        val eff1Safe  = if (h1.isNaN())  1f else h1.coerceIn(0f, 1f)
        val eff10Safe = if (h10.isNaN()) 1f else h10.coerceIn(0f, 1f)

        _state.value = ThermalInfo(
            state      = state as ThermalState,
            headroom1s  = if (useBatteryFallback) (1f - batteryTempC / 50f).coerceIn(0f, 1f) else eff1Safe,
            headroom10s = if (useBatteryFallback) (1f - batteryTempC / 50f).coerceIn(0f, 1f) else eff10Safe,
            source      = src as String,
            softLimit   = soft,
            hardLimit   = hard
        )
    }
}

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
