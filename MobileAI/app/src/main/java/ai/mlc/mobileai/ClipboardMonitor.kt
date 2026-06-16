package ai.mlc.mobileai

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import ai.localis.app.R
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ClipboardMonitor(
    private val context: Context,
    private val service: ForegroundInferenceService
) {
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var lastClip = ""

    fun start() {
        listener = ClipboardManager.OnPrimaryClipChangedListener {
            val clip = clipboard.primaryClip ?: return@OnPrimaryClipChangedListener
            // Ignore our own clips (API URL copy, AI response) — no "Send to AI?" self-notification
            val label = clip.description?.label?.toString()
            if (label == "api_url" || label == "MobileAI Response") return@OnPrimaryClipChangedListener
            val text = clip.getItemAt(0)?.text?.toString() ?: return@OnPrimaryClipChangedListener
            if (text.length > 10 && text != lastClip) {
                lastClip = text
                showSendToAiNotification(text)
            }
        }
        clipboard.addPrimaryClipChangedListener(listener!!)
    }

    fun stop() {
        listener?.let { clipboard.removePrimaryClipChangedListener(it) }
        listener = null
    }

    private fun showSendToAiNotification(prompt: String) {
        val sendIntent = Intent(context, ClipboardActionReceiver::class.java).apply {
            action = ClipboardActionReceiver.ACTION_SEND
            putExtra(ClipboardActionReceiver.EXTRA_PROMPT, prompt)
        }
        val pi = PendingIntent.getBroadcast(context, 0, sendIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val preview = if (prompt.length > 60) prompt.take(60) + "…" else prompt
        val notification = NotificationCompat.Builder(context, ForegroundInferenceService.CHANNEL_ID)
            .setContentTitle("Send to MobileAI?")
            .setContentText(preview)
            .setSmallIcon(R.drawable.ic_launcher)
            .addAction(0, "Send", pi)
            .setAutoCancel(true)
            .build()

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(CLIPBOARD_NOTIFICATION_ID, notification)
    }

    fun processClipboard(prompt: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val response = service.generateBlocking(prompt)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val preview = if (response.length > 80) response.take(80) + "…" else response
            val clip = android.content.ClipData.newPlainText("MobileAI Response", response)
            // Clipboard writes from background threads are silently dropped on Android 10+
            withContext(Dispatchers.Main) { clipboard.setPrimaryClip(clip) }
            val notification = NotificationCompat.Builder(context, ForegroundInferenceService.CHANNEL_ID)
                .setContentTitle("MobileAI Response (copied)")
                .setContentText(preview)
                .setSmallIcon(R.drawable.ic_launcher)
                .setAutoCancel(true)
                .build()
            nm.notify(CLIPBOARD_RESPONSE_ID, notification)
        }
    }

    companion object {
        const val CLIPBOARD_NOTIFICATION_ID = 1002
        const val CLIPBOARD_RESPONSE_ID = 1003
    }
}
