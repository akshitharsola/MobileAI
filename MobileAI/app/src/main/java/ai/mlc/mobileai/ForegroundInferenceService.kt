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

class ForegroundInferenceService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        apiServer?.stop()
        telegramPoller?.stop()
        super.onDestroy()
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

    suspend fun generateBlocking(prompt: String, maxTokens: Int = 512, modelId: String? = null): String {
        val resolved = if (modelId != null) resolveModelId(modelId) else null
        if (!ensureModelLoaded(resolved)) return "No model loaded. Download a model first."
        return chatState?.generateResponse(prompt, maxTokens) ?: "No model loaded"
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
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Localis")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
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
