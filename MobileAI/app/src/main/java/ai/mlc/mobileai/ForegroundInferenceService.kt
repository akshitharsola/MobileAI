package ai.mlc.mobileai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class ForegroundInferenceService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Single-thread executor: guarantees one thread owns inference, so Thread.sleep() actually gates GPU dispatch
    private val inferenceExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "localis-inference").apply {
            priority = android.os.Process.THREAD_PRIORITY_BACKGROUND
        }
    }
    private val inferenceDispatcher = inferenceExecutor.asCoroutineDispatcher()

    // Global mutex: only one inference runs at a time, queue drains the GPU before the next prefill.
    // Shared with AppViewModel so UI chat and API/Telegram requests can never overlap.
    val inferenceMutex = Mutex()

    var chatState: AppViewModel.ChatState? = null
    var appViewModel: AppViewModel? = null
    var apiServer: ApiServer? = null
    var telegramPoller: TelegramPoller? = null

    inner class LocalBinder : Binder() {
        fun getService(): ForegroundInferenceService = this@ForegroundInferenceService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Localis ready"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ShutdownReceiver.ACTION_SHUTDOWN) {
            shutdown()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        inferenceExecutor.shutdown()
        apiServer?.stop()
        telegramPoller?.stop()
        super.onDestroy()
    }

    fun shutdown() {
        getSharedPreferences("mobileai", Context.MODE_PRIVATE)
            .edit().putBoolean("service_enabled", false).apply()
        chatState?.let { state ->
            if (state.interruptable()) state.requestTerminateChat {}
        }
        apiServer?.stop()
        apiServer = null
        telegramPoller?.stop()
        telegramPoller = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    fun startApiServer(port: Int) {
        apiServer = ApiServer(this, port)
        serviceScope.launch { apiServer!!.start() }
        updateNotification("API server running on :$port")
    }

    fun startTelegramPoller(botToken: String) {
        telegramPoller?.stop()
        telegramPoller = TelegramPoller(botToken, this)
        telegramPoller!!.start()
        updateNotification("Telegram bot active")
    }

    fun stopTelegramPoller() {
        telegramPoller?.stop()
        telegramPoller = null
    }

    fun offloadModel() {
        val state = chatState ?: return
        if (state.interruptable()) {
            state.requestTerminateChat {}
        }
    }

    // Switch to a model by short name ("1.7b", "4b") or full model ID.
    // Returns true if switch was initiated, false if model not found/downloaded.
    fun switchModel(nameOrId: String): Boolean {
        val vm = appViewModel ?: return false
        val target = resolveModelId(nameOrId) ?: return false
        val modelState = vm.modelList.firstOrNull {
            it.modelConfig.modelId == target &&
            it.modelInitState.value == ModelInitState.Finished
        } ?: return false
        val modelPath = modelState.modelDirFile.absolutePath
        chatState?.requestReloadChat(modelState.modelConfig, modelPath)
        return true
    }

    // Ensure a model is loaded before generating. Loads default if nothing loaded.
    // Blocks until model is ready (up to 120s).
    private suspend fun ensureModelLoaded(preferredModelId: String? = null): Boolean {
        val state = chatState ?: return false
        if (state.chatable()) {
            // If a preferred model is requested and it's different, switch
            if (preferredModelId != null && state.modelName.value != preferredModelId) {
                switchModel(preferredModelId)
                // Wait for reload
                val deadline = System.currentTimeMillis() + 120_000
                while (!state.chatable() && System.currentTimeMillis() < deadline) delay(500)
            }
            return state.chatable()
        }

        // Nothing loaded — load default or preferred
        val vm = appViewModel ?: return false
        val prefs = getSharedPreferences("mobileai", Context.MODE_PRIVATE)
        val defaultId = preferredModelId
            ?: prefs.getString("default_model", null)
            ?: vm.modelList.firstOrNull { it.modelInitState.value == ModelInitState.Finished }
                ?.modelConfig?.modelId
            ?: return false

        val modelState = vm.modelList.firstOrNull {
            it.modelConfig.modelId == defaultId &&
            it.modelInitState.value == ModelInitState.Finished
        } ?: vm.modelList.firstOrNull { it.modelInitState.value == ModelInitState.Finished }
            ?: return false

        chatState?.requestReloadChat(modelState.modelConfig, modelState.modelDirFile.absolutePath)

        val deadline = System.currentTimeMillis() + 120_000
        while (!state.chatable() && System.currentTimeMillis() < deadline) delay(500)
        return state.chatable()
    }

    suspend fun generateBlocking(prompt: String, maxTokens: Int = 2048, modelId: String? = null): String {
        val resolved = if (modelId != null) resolveModelId(modelId) else null
        if (!ensureModelLoaded(resolved)) return "No model loaded. Download a model first."
        val cappedTokens = maxTokens.coerceAtMost(4096)
        // Serialize all inference requests — prevents concurrent prefill which saturates GPU queue
        return inferenceMutex.withLock {
            try {
                withTimeout(180_000L) {
                    withContext(inferenceDispatcher) {
                        // Pre-prefill delay proportional to prompt length: lets GPU drain from any prior work
                        val promptWords = prompt.split(" ").size
                        val prePrefillMs = (promptWords * 2L).coerceIn(100L, 2000L)
                        Thread.sleep(prePrefillMs)
                        val result = chatState?.generateResponse(prompt, cappedTokens) ?: "No model loaded"
                        // Post-inference GPU drain cooldown before releasing mutex for next request
                        Thread.sleep(3000)
                        result
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                "Request timed out after 180 seconds."
            }
        }
    }

    // Resolve short names like "1.7b" → full model ID
    fun resolveModelId(nameOrId: String): String? {
        val vm = appViewModel ?: return null
        val lower = nameOrId.lowercase().trim()
        return when {
            lower == "1.7b" || lower == "1.7" || lower == "small" ->
                vm.modelList.firstOrNull { it.modelConfig.modelId.contains("1.7B", ignoreCase = true) }?.modelConfig?.modelId
            lower == "4b" || lower == "4" || lower == "large" ->
                vm.modelList.firstOrNull { it.modelConfig.modelId.contains("4B", ignoreCase = true) }?.modelConfig?.modelId
            else ->
                vm.modelList.firstOrNull { it.modelConfig.modelId.equals(nameOrId, ignoreCase = true) }?.modelConfig?.modelId
        }
    }

    fun isModelLoaded(): Boolean = chatState?.chatable() == true
    fun loadedModelName(): String {
        val name = chatState?.modelName?.value ?: ""
        return if (name.isBlank()) "none" else name
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)

        val shutdownIntent = Intent(this, ShutdownReceiver::class.java).apply {
            action = ShutdownReceiver.ACTION_SHUTDOWN
        }
        val shutdownPi = PendingIntent.getBroadcast(
            this, 1, shutdownIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Localis")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Shut Down", shutdownPi)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.channel_desc) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "localis_inference"
        const val NOTIFICATION_ID = 1001
    }
}
