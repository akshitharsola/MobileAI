package ai.mlc.mobileai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ClipboardActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SEND) {
            val prompt = intent.getStringExtra(EXTRA_PROMPT) ?: return
            val serviceIntent = Intent(context, ForegroundInferenceService::class.java).apply {
                action = ACTION_SEND
                putExtra(EXTRA_PROMPT, prompt)
            }
            context.startService(serviceIntent)
        }
    }

    companion object {
        const val ACTION_SEND = "ai.localis.app.CLIPBOARD_SEND"
        const val EXTRA_PROMPT = "prompt"
    }
}
