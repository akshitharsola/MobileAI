package ai.mlc.mobileai

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private data class TgUpdate(
    @SerializedName("update_id") val updateId: Long,
    val message: TgMessage? = null
)
private data class TgMessage(
    @SerializedName("message_id") val messageId: Long,
    val text: String? = null,
    val chat: TgChat
)
private data class TgChat(val id: Long)
private data class TgGetUpdatesResponse(val ok: Boolean, val result: List<TgUpdate> = emptyList())
private data class TgSendMessage(
    @SerializedName("chat_id") val chatId: Long,
    val text: String
)

class TelegramPoller(
    private val botToken: String,
    private val service: ForegroundInferenceService
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val baseUrl = "https://api.telegram.org/bot$botToken"
    private var offset = 0L
    private var running = false
    private var job: Job? = null

    fun start() {
        running = true
        job = CoroutineScope(Dispatchers.IO).launch {
            while (running) {
                try {
                    poll()
                } catch (e: Exception) {
                    Log.e("TelegramPoller", "Poll error: ${e.message}")
                    delay(5000)
                }
            }
        }
    }

    fun stop() {
        running = false
        job?.cancel()
    }

    private fun poll() {
        val url = "$baseUrl/getUpdates?timeout=25&offset=$offset"
        val req = Request.Builder().url(url).build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: return
        val updates = gson.fromJson(body, TgGetUpdatesResponse::class.java)
        if (!updates.ok) return

        for (update in updates.result) {
            offset = update.updateId + 1
            val msg = update.message ?: continue
            val text = msg.text ?: continue
            handleMessage(msg.chat.id, text)
        }
    }

    private fun handleMessage(chatId: Long, text: String) {
        when {
            text == "/start" -> sendMessage(chatId,
                "Localis is running.\n\nSend any prompt for inference.\n\n" +
                "Commands:\n/status — show active model\n/model 1.7b — switch to Qwen3-1.7B\n/model 4b — switch to Qwen3-4B\n/ip — show phone IP"
            )
            text == "/status" -> {
                val loaded = service.isModelLoaded()
                val name = service.loadedModelName()
                sendMessage(chatId, if (loaded) "Active model: $name" else "No model loaded. Use /model 1.7b or /model 4b to load one.")
            }
            text.startsWith("/model ") -> {
                val arg = text.removePrefix("/model ").trim()
                if (arg.isBlank()) {
                    sendMessage(chatId, "Usage: /model 1.7b  or  /model 4b")
                    return
                }
                val switched = service.switchModel(arg)
                if (switched) {
                    val resolved = service.resolveModelId(arg) ?: arg
                    sendMessage(chatId, "Switching to $resolved… send a message when ready.")
                } else {
                    sendMessage(chatId, "Model '$arg' not found or not downloaded. Available: 1.7b, 4b")
                }
            }
            text == "/model" -> sendMessage(chatId,
                "Current: ${service.loadedModelName()}\n\nSwitch with:\n/model 1.7b\n/model 4b"
            )
            text == "/ip" -> {
                val ip = try {
                    var found = ""
                    for (iface in java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                        if (!iface.isUp || iface.isLoopback) continue
                        for (addr in java.util.Collections.list(iface.inetAddresses)) {
                            if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                                found = addr.hostAddress ?: ""
                            }
                        }
                    }
                    if (found.isNotEmpty()) found else "unknown"
                } catch (e: Exception) { "error: ${e.message}" }
                sendMessage(chatId, "Phone IP: $ip\n\nTo update VM config: /ip $ip")
            }
            else -> {
                sendMessage(chatId, "⏳ Processing…")
                val response = try {
                    runBlocking { service.generateBlocking(text) }
                } catch (e: Exception) { "Error: ${e.message}" }
                sendMessage(chatId, response)
            }
        }
    }

    private fun sendMessage(chatId: Long, text: String) {
        try {
            val payload = gson.toJson(TgSendMessage(chatId, text))
            val body = payload.toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$baseUrl/sendMessage").post(body).build()
            client.newCall(req).execute().close()
        } catch (e: Exception) {
            Log.e("TelegramPoller", "Send error: ${e.message}")
        }
    }
}
