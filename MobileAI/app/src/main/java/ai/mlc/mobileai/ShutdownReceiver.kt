package ai.mlc.mobileai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ShutdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SHUTDOWN) {
            val serviceIntent = Intent(context, ForegroundInferenceService::class.java).apply {
                action = ACTION_SHUTDOWN
            }
            context.startService(serviceIntent)
        }
    }

    companion object {
        const val ACTION_SHUTDOWN = "ai.localis.app.SHUTDOWN"
    }
}
