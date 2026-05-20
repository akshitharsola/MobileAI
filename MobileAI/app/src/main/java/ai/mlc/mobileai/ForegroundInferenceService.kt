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
    var apiServer: ApiServer? = null
    var telegramPoller: TelegramPoller? = null

    inner class LocalBinder : Binder() {
        fun getService(): ForegroundInferenceService = this@ForegroundInferenceService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("MobileAI ready"))
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
        telegramPoller = TelegramPoller(botToken) { prompt ->
            runBlocking { generateBlocking(prompt) }
        }
        serviceScope.launch { telegramPoller!!.start() }
        updateNotification("Telegram bot active")
    }

    fun stopTelegramPoller() {
        telegramPoller?.stop()
        telegramPoller = null
    }

    suspend fun generateBlocking(prompt: String, maxTokens: Int = 512): String {
        return chatState?.generateResponse(prompt, maxTokens) ?: "No model loaded"
    }

    fun isModelLoaded(): Boolean = chatState?.chatable() == true
    fun loadedModelName(): String = chatState?.modelName?.value ?: "none"

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MobileAI")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
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
        const val CHANNEL_ID = "mobileai_inference"
        const val NOTIFICATION_ID = 1001
    }
}
