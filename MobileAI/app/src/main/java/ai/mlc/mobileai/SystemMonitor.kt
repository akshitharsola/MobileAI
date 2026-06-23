package ai.mlc.mobileai

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
import java.util.concurrent.Executor
import java.util.concurrent.Executors

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
