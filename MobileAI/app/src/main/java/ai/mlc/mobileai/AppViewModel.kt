// Based on MLCChat from MLC-LLM (https://github.com/mlc-ai/mlc-llm), Apache 2.0 License
// Extended by Akshit Harsola — adds generateResponse(), RAM tracking, service bridge
// Migrated to Google LiteRT-LM (LiteRtLmEngine) — v4.0
package ai.mlc.mobileai

import android.app.ActivityManager
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.channels.Channels
import java.util.UUID
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import android.util.Base64
import android.util.Log

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    val modelList = emptyList<ModelState>().toMutableStateList()
    val chatState = ChatState()
    private var showAlert = mutableStateOf(false)
    private var alertMessage = mutableStateOf("")
    private val gson = Gson()

    // Set by MainActivity after service bind — same mutex generateBlocking uses, so UI chat
    // and API/Telegram inference are serialized and never overlap on the GPU
    var inferenceMutex: Mutex? = null

    val ramUsageMB = mutableStateOf(0L)
    val totalRamMB = mutableStateOf(0L)
    val thermalGovernor = ThermalGovernor(app)
    val inferenceStats = mutableStateOf(InferenceStats())
    val inferenceHistory = emptyList<InferenceStats>().toMutableStateList()

    companion object {
        const val ModelConfigFilename = "localis-model-config.json"
    }

    init {
        loadAppConfig()
        updateRamUsage()
        thermalGovernor.start()
    }

    override fun onCleared() {
        super.onCleared()
        thermalGovernor.stop()
    }

    fun updateRamUsage() {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        totalRamMB.value = mi.totalMem / (1024 * 1024)
        ramUsageMB.value = (mi.totalMem - mi.availMem) / (1024 * 1024)
    }

    fun isShowingAlert(): Boolean = showAlert.value
    fun errorMessage(): String = alertMessage.value

    fun dismissAlert() {
        require(showAlert.value)
        showAlert.value = false
    }

    fun copyError() {
        require(showAlert.value)
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Localis", errorMessage()))
    }

    private fun issueAlert(error: String) {
        showAlert.value = true
        alertMessage.value = error
    }

    private fun loadAppConfig() {
        modelList.clear()
        try {
            val bundledJson = app.assets.open(ModelConfigFilename).bufferedReader().use { it.readText() }
            val config = gson.fromJson(bundledJson, LocalisModelConfig::class.java)
            for (record in config.model_list) {
                modelList.add(ModelState(record))
            }
        } catch (e: Exception) {
            issueAlert("Failed to load model config: ${e.localizedMessage}")
        }
    }

    inner class ModelState(val record: LocalisModelRecord) {
        var modelInitState = mutableStateOf(
            if (LiteRtLmEngine.modelFileExists(app, record.filename)) ModelInitState.Finished
            else ModelInitState.Paused
        )
        val progress = mutableStateOf(0)
        val total = mutableStateOf(1)
        val id: UUID = UUID.randomUUID()
        private var downloadJob: Job? = null

        // Shim so existing StartView.kt code calling modelState.modelConfig.modelId still compiles
        val modelConfig: FakeModelConfig get() = FakeModelConfig(record)

        fun estimatedVramGB(): String =
            String.format("%.1f", record.estimated_size_bytes.toFloat() / (1024 * 1024 * 1024))

        fun fileSizeMB(): Long = LiteRtLmEngine.modelFileSizeMB(app, record.filename)

        fun startChat() { chatState.requestReloadChat(record) }

        fun handleStart() {
            modelInitState.value = ModelInitState.Downloading
            downloadJob = viewModelScope.launch(Dispatchers.IO) {
                try { downloadModel() }
                catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        issueAlert("Download failed: ${e.localizedMessage}")
                        modelInitState.value = ModelInitState.Paused
                    }
                }
            }
        }

        fun handlePause() { downloadJob?.cancel(); modelInitState.value = ModelInitState.Paused }

        fun handleDelete() {
            downloadJob?.cancel()
            if (chatState.modelName.value == record.model_id) {
                chatState.requestTerminateChat {
                    File(app.getExternalFilesDir(""), record.filename).delete()
                    progress.value = 0; total.value = 1
                    modelInitState.value = ModelInitState.Paused
                }
            } else {
                File(app.getExternalFilesDir(""), record.filename).delete()
                progress.value = 0; total.value = 1
                modelInitState.value = ModelInitState.Paused
            }
        }

        fun handleClear() { handleDelete() }

        private suspend fun downloadModel() {
            val destFile = File(app.getExternalFilesDir(""), record.filename)
            if (destFile.exists()) {
                withContext(Dispatchers.Main) { modelInitState.value = ModelInitState.Finished }
                return
            }
            val prefs = app.getSharedPreferences("mobileai", android.content.Context.MODE_PRIVATE)
            val hfToken = prefs.getString("hf_token", "") ?: ""
            val hfUrl = "https://huggingface.co/litert-community/${record.hf_repo}/resolve/main/${record.filename}"
            val tempFile = File(app.getExternalFilesDir(""), "${record.filename}.tmp")
            try {
                val connection = (URL(hfUrl).openConnection() as java.net.HttpURLConnection).apply {
                    if (hfToken.isNotBlank()) setRequestProperty("Authorization", "Bearer $hfToken")
                    connect()
                }
                val responseCode = connection.responseCode
                if (responseCode == 401) {
                    throw Exception("HuggingFace auth required. Add your HF token in Settings, and accept the model license on huggingface.co/litert-community/${record.hf_repo}")
                }
                if (responseCode !in 200..299) {
                    throw Exception("Download failed: HTTP $responseCode")
                }
                val contentLength = connection.contentLengthLong
                withContext(Dispatchers.Main) { total.value = if (contentLength > 0) 100 else 1 }
                var bytesRead = 0L
                val channel = Channels.newChannel(connection.inputStream)
                FileOutputStream(tempFile).use { out ->
                    val buffer = java.nio.ByteBuffer.allocate(65536)
                    while (true) {
                        buffer.clear()
                        val n = channel.read(buffer)
                        if (n <= 0) break
                        bytesRead += n
                        out.write(buffer.array(), 0, n)
                        if (contentLength > 0) {
                            val pct = (bytesRead * 100 / contentLength).toInt()
                            withContext(Dispatchers.Main) { progress.value = pct }
                        }
                    }
                }
            } catch (e: Exception) {
                tempFile.delete()
                throw e
            }
            if (tempFile.exists()) tempFile.renameTo(destFile)
            withContext(Dispatchers.Main) { progress.value = 100; modelInitState.value = ModelInitState.Finished }
        }
    }

    inner class ChatState {
        val messages = emptyList<MessageData>().toMutableStateList()
        val report = mutableStateOf("")
        val modelName = mutableStateOf("")
        private var modelChatState = mutableStateOf(ModelChatState.Ready)
            @Synchronized get
            @Synchronized set
        val engine = LiteRtLmEngine(app)
        private var historyMessages = mutableListOf<Pair<String, String>>() // (role, content)
        private var currentRecord: LocalisModelRecord? = null
        private val scope = CoroutineScope(Dispatchers.Main + Job())
        private var imageUri: Uri? = null

        private fun clearHistory() { messages.clear(); report.value = ""; historyMessages.clear() }
        private fun switchToResetting() { modelChatState.value = ModelChatState.Resetting }
        private fun switchToGenerating() { modelChatState.value = ModelChatState.Generating }
        private fun switchToReloading() { modelChatState.value = ModelChatState.Reloading }
        private fun switchToReady() { modelChatState.value = ModelChatState.Ready }
        private fun switchToFailed() { modelChatState.value = ModelChatState.Failed }
        private fun switchToTerminating() { modelChatState.value = ModelChatState.Terminating }

        fun requestResetChat() {
            require(interruptable())
            scope.launch {
                switchToResetting()
                imageUri = null
                clearHistory()
                switchToReady()
            }
        }

        fun requestTerminateChat(callback: () -> Unit) {
            require(interruptable())
            switchToTerminating()
            scope.launch(Dispatchers.IO) {
                try { engine.unload() } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    clearHistory()
                    modelName.value = ""
                    currentRecord = null
                    switchToReady()
                    callback()
                }
            }
        }

        fun requestReloadChat(record: LocalisModelRecord) {
            if (this.modelName.value == record.model_id && currentRecord?.filename == record.filename) return
            require(interruptable())
            switchToReloading()
            mainReloadChat(record)
        }

        private fun mainReloadChat(record: LocalisModelRecord) {
            clearHistory()
            this.modelName.value = record.model_id
            this.currentRecord = record
            scope.launch(Dispatchers.IO) {
                withContext(Dispatchers.Main) { Toast.makeText(app, "Loading model…", Toast.LENGTH_SHORT).show() }
                val ok = try {
                    engine.unload()
                    engine.load(
                        record.model_id,
                        LiteRtLmEngine.modelFilePath(app, record.filename),
                        record.backend
                    )
                    true
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        appendMessage(MessageRole.Assistant, "Error loading model: ${e.localizedMessage}")
                        modelName.value = ""
                        currentRecord = null
                        switchToFailed()
                    }
                    false
                }
                if (!ok) return@launch
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, "Ready to chat", Toast.LENGTH_SHORT).show()
                    switchToReady()
                }
            }
        }

        fun requestImageBitmap(uri: Uri?) {
            require(chatable())
            switchToGenerating()
            scope.launch(Dispatchers.IO) {
                imageUri = uri
                withContext(Dispatchers.Main) {
                    report.value = "Image ready, ask your question."
                    if (modelChatState.value == ModelChatState.Generating) switchToReady()
                }
            }
        }

        private fun bitmapToURL(bm: Bitmap): String {
            val scaled = Bitmap.createScaledBitmap(bm, 336, 336, true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 100, out)
            scaled.recycle()
            return "data:image/jpg;base64,${Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)}"
        }

        fun requestGenerate(prompt: String, activity: android.app.Activity) {
            require(chatable())
            switchToGenerating()
            appendMessage(MessageRole.User, prompt)
            appendMessage(MessageRole.Assistant, "")
            // Capture URI reference on main thread — actual decode happens in IO below
            val capturedUri = imageUri
            imageUri = null
            val prefs = app.getSharedPreferences("mobileai", Context.MODE_PRIVATE)
            val maxTokens = prefs.getInt("max_tokens", 2048).coerceIn(256, 4096)
            val noThink = prefs.getBoolean("no_think", false)
            val thinkMaxTokens = prefs.getInt("think_max_tokens", 1024).coerceIn(256, 2048)
            scope.launch(Dispatchers.IO) {
                // Image decode runs on IO thread — LiteRT-LM is text-only here, but we keep the
                // decode/scale path so a multimodal build can wire it in later.
                if (capturedUri != null) {
                    val bitmap = activity.contentResolver.openInputStream(capturedUri)?.use { i ->
                        BitmapFactory.decodeStream(i)
                    }
                    if (bitmap != null) {
                        // Reserved for multimodal: data URL produced but not yet sent to the engine.
                        bitmapToURL(bitmap)
                    }
                }
                // Snapshot history (excluding the just-appended user turn) for the engine
                val historySnapshot = historyMessages.toList()
                historyMessages.add(Pair("user", prompt))
                val systemPrompt = if (noThink) "/no_think" else null
                scope.launch(Dispatchers.Default) {
                    // Deprioritize inference so UI/system threads stay responsive
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    suspend fun runInference() {
                        var streaming = ""
                        var completionTokenCount = 0
                        var firstTokenMs = 0L
                        val startMs = System.currentTimeMillis()
                        var lastUiUpdateMs = 0L
                        var thinkTokenCount = 0
                        var thinkBudgetInjected = false
                        var tokensSinceBreath = 0
                        val ok = try {
                            engine.streamResponse(
                                prompt = prompt,
                                systemPrompt = systemPrompt,
                                history = historySnapshot,
                                maxTokens = maxTokens
                            ).collect { token ->
                                if (firstTokenMs == 0L) firstTokenMs = System.currentTimeMillis() - startMs
                                streaming += token
                                completionTokenCount++
                                tokensSinceBreath++
                                // Track think-phase tokens; inject </think> once budget is hit
                                if (!thinkBudgetInjected) {
                                    val inThink = streaming.contains("<think>") && !streaming.contains("</think>")
                                    if (inThink) {
                                        thinkTokenCount++
                                        if (thinkTokenCount >= thinkMaxTokens) {
                                            streaming += "\n[think budget reached]\n</think>\n"
                                            thinkBudgetInjected = true
                                        }
                                    }
                                }
                                // GPU breath window every 10 tokens — lets display pipeline sneak in a frame
                                if (tokensSinceBreath >= 10) {
                                    tokensSinceBreath = 0
                                    delay(50)
                                }
                                // Batch UI updates: push at most every 250ms to reduce IPC/Binder flooding
                                val now = System.currentTimeMillis()
                                if (now - lastUiUpdateMs >= 250L) {
                                    lastUiUpdateMs = now
                                    val (think, answer, thinkOpen) = parseThinkBlocks(streaming)
                                    withContext(Dispatchers.Main) { updateMessage(MessageRole.Assistant, answer, think, thinkOpen) }
                                }
                                // Thermal-driven token pacing — use delay() to suspend, not Thread.sleep()
                                when {
                                    this@AppViewModel.thermalGovernor.hardLimit -> delay(500)
                                    this@AppViewModel.thermalGovernor.softLimit -> delay(150)
                                    else -> delay(30)
                                }
                            }
                            true
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                appendMessage(MessageRole.Assistant, "Error: ${e.localizedMessage}")
                                switchToFailed()
                            }
                            false
                        }
                        if (!ok) return
                        // Final UI update — if think block never closed, force-close it
                        val streamingFinal = if (streaming.contains("<think>") && !streaming.contains("</think>"))
                            streaming + "\n[think exhausted — model produced no answer]\n</think>"
                        else streaming
                        val (thinkFinal, answerFinal, _) = parseThinkBlocks(streamingFinal)
                        withContext(Dispatchers.Main) { updateMessage(MessageRole.Assistant, answerFinal, thinkFinal, false) }
                        if (streamingFinal.isNotEmpty()) {
                            // Store only the answer in history — re-feeding <think> content wastes context
                            val (_, finalAnswer, _) = parseThinkBlocks(streamingFinal)
                            historyMessages.add(Pair("assistant", finalAnswer.ifEmpty { streamingFinal }))
                        } else {
                            // No output — drop the user turn we optimistically added
                            if (historyMessages.isNotEmpty()) historyMessages.removeAt(historyMessages.size - 1)
                        }
                        val totalMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1)
                        val tps = completionTokenCount.toFloat() / (totalMs / 1000f).coerceAtLeast(0.001f)
                        val entry = InferenceStats(
                            tokensPerSecond = tps,
                            promptTokens = prompt.length / 4,
                            completionTokens = completionTokenCount,
                            timeToFirstTokenMs = firstTokenMs,
                            lastRequestMs = System.currentTimeMillis()
                        )
                        withContext(Dispatchers.Main) {
                            this@AppViewModel.inferenceStats.value = entry
                            this@AppViewModel.inferenceHistory.add(0, entry)
                            if (this@AppViewModel.inferenceHistory.size > 20)
                                this@AppViewModel.inferenceHistory.removeAt(this@AppViewModel.inferenceHistory.size - 1)
                        }
                    }
                    // Same mutex as generateBlocking: UI chat queues behind API/Telegram inference
                    val mutex = this@AppViewModel.inferenceMutex
                    if (mutex != null) mutex.withLock { runInference() } else runInference()
                    withContext(Dispatchers.Main) {
                        if (modelChatState.value == ModelChatState.Generating) switchToReady()
                    }
                }
            }
        }

        // Used by ForegroundInferenceService for headless inference
        suspend fun generateResponse(prompt: String, maxTokens: Int = 512): String {
            if (!chatable()) return "Model not ready"
            val noThink = app.getSharedPreferences("mobileai", Context.MODE_PRIVATE)
                .getBoolean("no_think", false)
            val systemPrompt = if (noThink) "/no_think" else null
            val result = StringBuilder()
            var firstTokenMs = 0L
            val startMs = System.currentTimeMillis()
            var completionTokenCount = 0
            var tokensSinceBreath = 0

            try {
                engine.streamResponse(
                    prompt = prompt,
                    systemPrompt = systemPrompt,
                    history = emptyList(),
                    maxTokens = maxTokens
                ).collect { token ->
                    if (firstTokenMs == 0L) firstTokenMs = System.currentTimeMillis() - startMs
                    result.append(token)
                    completionTokenCount++
                    tokensSinceBreath++
                    // GPU breath window every 10 tokens — lets display pipeline sneak in a frame
                    if (tokensSinceBreath >= 10) {
                        tokensSinceBreath = 0
                        delay(50)
                    }
                    // Thermal-driven token pacing for headless path — delay() suspends, Thread.sleep() blocks
                    when {
                        this@AppViewModel.thermalGovernor.hardLimit -> delay(500)
                        this@AppViewModel.thermalGovernor.softLimit -> delay(150)
                        else -> delay(30)
                    }
                }
            } catch (e: Exception) {
                return "Error: ${e.localizedMessage}"
            }
            val totalMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1)
            val tps = completionTokenCount.toFloat() / (totalMs / 1000f).coerceAtLeast(0.001f)
            scope.launch {
                val entry = InferenceStats(
                    tokensPerSecond = tps,
                    promptTokens = prompt.length / 4,
                    completionTokens = completionTokenCount,
                    timeToFirstTokenMs = firstTokenMs,
                    lastRequestMs = System.currentTimeMillis()
                )
                this@AppViewModel.inferenceStats.value = entry
                this@AppViewModel.inferenceHistory.add(0, entry)
                if (this@AppViewModel.inferenceHistory.size > 20)
                    this@AppViewModel.inferenceHistory.removeAt(this@AppViewModel.inferenceHistory.size - 1)
            }
            return result.toString().trim()
        }

        private fun appendMessage(role: MessageRole, text: String) { messages.add(MessageData(role, text)) }
        private fun updateMessage(role: MessageRole, text: String, thinkContent: String = "", isThinkOpen: Boolean = false) {
            // copy() preserves the message id so LazyColumn keys stay stable during streaming
            val last = messages[messages.size - 1]
            messages[messages.size - 1] = last.copy(role = role, text = text, thinkContent = thinkContent, isThinkOpen = isThinkOpen)
        }
        fun chatable(): Boolean = modelChatState.value == ModelChatState.Ready && modelName.value.isNotBlank()
        fun interruptable(): Boolean = modelChatState.value == ModelChatState.Ready ||
                modelChatState.value == ModelChatState.Generating ||
                modelChatState.value == ModelChatState.Failed
    }
}

data class FakeModelConfig(val record: LocalisModelRecord) {
    val modelId: String get() = record.model_id
}

enum class ModelInitState { Initializing, Indexing, Paused, Downloading, Pausing, Clearing, Deleting, Finished }
enum class ModelChatState { Generating, Resetting, Reloading, Terminating, Ready, Failed }
enum class MessageRole { Assistant, User }

data class DownloadTask(val url: URL, val file: File)
data class MessageData(
    val role: MessageRole,
    val text: String,
    val id: UUID = UUID.randomUUID(),
    var imageUri: Uri? = null,
    val thinkContent: String = "",
    val isThinkOpen: Boolean = false
)

// Splits Qwen3-style output into (think content, answer content, isThinkOpen).
// Tolerant of an unclosed <think> while streaming. Used by chat UI and ApiServer.
fun parseThinkBlocks(text: String): Triple<String, String, Boolean> {
    val think = StringBuilder()
    val answer = StringBuilder()
    var i = 0
    var open = false
    while (i < text.length) {
        if (!open) {
            val start = text.indexOf("<think>", i)
            if (start == -1) { answer.append(text, i, text.length); break }
            answer.append(text, i, start)
            i = start + "<think>".length
            open = true
        } else {
            val end = text.indexOf("</think>", i)
            if (end == -1) { think.append(text, i, text.length); break }
            think.append(text, i, end).append('\n')
            i = end + "</think>".length
            open = false
        }
    }
    return Triple(think.toString().trim(), answer.toString().trim(), open)
}

data class InferenceStats(
    val tokensPerSecond: Float = 0f,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val timeToFirstTokenMs: Long = 0L,
    val lastRequestMs: Long = 0L
)
