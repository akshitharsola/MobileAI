// Based on MLCChat from MLC-LLM (https://github.com/mlc-ai/mlc-llm), Apache 2.0 License
// Extended by Akshit Harsola — adds generateResponse(), RAM tracking, service bridge
package ai.mlc.mobileai

import android.app.ActivityManager
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.channels.Channels
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import android.util.Base64
import android.util.Log

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val modelList = emptyList<ModelState>().toMutableStateList()
    val chatState = ChatState()
    val modelSampleList = emptyList<ModelRecord>().toMutableStateList()
    private var showAlert = mutableStateOf(false)
    private var alertMessage = mutableStateOf("")
    private var appConfig = AppConfig(
        emptyList<String>().toMutableList(),
        emptyList<ModelRecord>().toMutableList()
    )
    private val application = getApplication<Application>()
    private val appDirFile = application.getExternalFilesDir("")
    private val gson = Gson()
    private val modelIdSet = emptySet<String>().toMutableSet()

    // Set by MainActivity after service bind — same mutex generateBlocking uses, so UI chat
    // and API/Telegram inference are serialized and never overlap on the GPU
    var inferenceMutex: Mutex? = null

    val ramUsageMB = mutableStateOf(0L)
    val totalRamMB = mutableStateOf(0L)
    val systemMonitor = SystemMonitor(application)
    val thermalGovernor = ThermalGovernor(application)
    val inferenceStats = mutableStateOf(InferenceStats())
    val inferenceHistory = emptyList<InferenceStats>().toMutableStateList()

    companion object {
        const val AppConfigFilename = "mlc-app-config.json"
        const val ModelConfigFilename = "mlc-chat-config.json"
        const val ParamsConfigFilename = "tensor-cache.json"
        const val ModelUrlSuffix = "resolve/main/"
    }

    init {
        loadAppConfig()
        updateRamUsage()
        systemMonitor.start()
        thermalGovernor.start()
    }

    override fun onCleared() {
        super.onCleared()
        systemMonitor.stop()
        thermalGovernor.stop()
    }

    fun updateRamUsage() {
        val am = application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
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
        val clipboard = application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MobileAI", errorMessage()))
    }

    private fun issueAlert(error: String) {
        showAlert.value = true
        alertMessage.value = error
    }

    private fun loadAppConfig() {
        // Always use the bundled assets config as source of truth.
        val appConfigFile = File(appDirFile, AppConfigFilename)
        appConfigFile.delete()
        val bundledJson = application.assets.open(AppConfigFilename).bufferedReader().use { it.readText() }
        appConfig = gson.fromJson(bundledJson, AppConfig::class.java)
        appConfig.modelLibs = emptyList<String>().toMutableList()
        modelList.clear()
        modelIdSet.clear()
        modelSampleList.clear()
        // Migration: pre-v2.7 builds blacklisted deleted models forever; drop the blacklist
        // so previously-deleted models reappear with a Download button.
        application.getSharedPreferences("mobileai", Context.MODE_PRIVATE)
            .edit().remove("deleted_model_ids").apply()
        for (modelRecord in appConfig.modelList) {
            appConfig.modelLibs.add(modelRecord.modelLib)
            val modelDirFile = File(appDirFile, modelRecord.modelId)
            val modelConfigFile = File(modelDirFile, ModelConfigFilename)
            if (modelConfigFile.exists()) {
                val modelConfig = gson.fromJson(modelConfigFile.readText(), ModelConfig::class.java)
                modelConfig.modelId = modelRecord.modelId
                modelConfig.modelLib = modelRecord.modelLib
                modelConfig.estimatedVramBytes = modelRecord.estimatedVramBytes
                addModelConfig(modelConfig, modelRecord.modelUrl, true)
            } else {
                downloadModelConfig(
                    if (modelRecord.modelUrl.endsWith("/")) modelRecord.modelUrl else "${modelRecord.modelUrl}/",
                    modelRecord,
                    true
                )
            }
        }
    }

    private fun updateAppConfig(action: () -> Unit) {
        action()
        File(appDirFile, AppConfigFilename).writeText(gson.toJson(appConfig))
    }

    private fun addModelConfig(modelConfig: ModelConfig, modelUrl: String, isBuiltin: Boolean) {
        require(!modelIdSet.contains(modelConfig.modelId))
        modelIdSet.add(modelConfig.modelId)
        modelList.add(
            ModelState(
                modelConfig,
                modelUrl + if (modelUrl.endsWith("/")) "" else "/",
                File(appDirFile, modelConfig.modelId)
            )
        )
        if (!isBuiltin) {
            updateAppConfig {
                appConfig.modelList.add(
                    ModelRecord(modelUrl, modelConfig.modelId, modelConfig.estimatedVramBytes, modelConfig.modelLib)
                )
            }
        }
    }

    private fun isModelConfigAllowed(modelConfig: ModelConfig): Boolean {
        if (appConfig.modelLibs.contains(modelConfig.modelLib)) return true
        viewModelScope.launch { issueAlert("Model lib ${modelConfig.modelLib} is not supported.") }
        return false
    }

    private fun downloadModelConfig(modelUrl: String, modelRecord: ModelRecord, isBuiltin: Boolean) {
        thread(start = true) {
            try {
                val url = URL("${modelUrl}${ModelUrlSuffix}${ModelConfigFilename}")
                val tempId = UUID.randomUUID().toString()
                val tempFile = File(application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), tempId)
                url.openStream().use {
                    Channels.newChannel(it).use { src ->
                        FileOutputStream(tempFile).use { fo -> fo.channel.transferFrom(src, 0, Long.MAX_VALUE) }
                    }
                }
                require(tempFile.exists())
                viewModelScope.launch {
                    try {
                        val modelConfig = gson.fromJson(tempFile.readText(), ModelConfig::class.java)
                        modelConfig.modelId = modelRecord.modelId
                        modelConfig.modelLib = modelRecord.modelLib
                        modelConfig.estimatedVramBytes = modelRecord.estimatedVramBytes
                        if (modelIdSet.contains(modelConfig.modelId)) { tempFile.delete(); return@launch }
                        if (!isModelConfigAllowed(modelConfig)) { tempFile.delete(); return@launch }
                        val modelConfigFile = File(File(appDirFile, modelConfig.modelId), ModelConfigFilename)
                        tempFile.copyTo(modelConfigFile, overwrite = true)
                        tempFile.delete()
                        addModelConfig(modelConfig, modelUrl, isBuiltin)
                    } catch (e: Exception) {
                        viewModelScope.launch { issueAlert("Add model failed: ${e.localizedMessage}") }
                    }
                }
            } catch (e: Exception) {
                viewModelScope.launch { issueAlert("Download model config failed: ${e.localizedMessage}") }
            }
        }
    }

    inner class ModelState(
        val modelConfig: ModelConfig,
        private val modelUrl: String,
        val modelDirFile: File
    ) {
        var modelInitState = mutableStateOf(ModelInitState.Initializing)
        private var paramsConfig = ParamsConfig(emptyList())
        val progress = mutableStateOf(0)
        val total = mutableStateOf(1)
        val id: UUID = UUID.randomUUID()
        private val remainingTasks = emptySet<DownloadTask>().toMutableSet()
        private val downloadingTasks = emptySet<DownloadTask>().toMutableSet()
        private val maxDownloadTasks = 3
        private val gson = Gson()

        init { switchToInitializing() }

        private fun switchToInitializing() {
            val paramsConfigFile = File(modelDirFile, ParamsConfigFilename)
            if (paramsConfigFile.exists()) { loadParamsConfig(); switchToIndexing() }
            else downloadParamsConfig()
        }

        private fun loadParamsConfig() {
            val f = File(modelDirFile, ParamsConfigFilename)
            require(f.exists())
            paramsConfig = gson.fromJson(f.readText(), ParamsConfig::class.java)
        }

        private fun downloadParamsConfig() {
            thread(start = true) {
                val url = URL("${modelUrl}${ModelUrlSuffix}${ParamsConfigFilename}")
                val tempId = UUID.randomUUID().toString()
                val tempFile = File(modelDirFile, tempId)
                url.openStream().use {
                    Channels.newChannel(it).use { src ->
                        FileOutputStream(tempFile).use { fo -> fo.channel.transferFrom(src, 0, Long.MAX_VALUE) }
                    }
                }
                val paramsConfigFile = File(modelDirFile, ParamsConfigFilename)
                tempFile.renameTo(paramsConfigFile)
                viewModelScope.launch { loadParamsConfig(); switchToIndexing() }
            }
        }

        fun handleStart() { switchToDownloading() }
        fun handlePause() { switchToPausing() }

        fun handleClear() {
            require(modelInitState.value == ModelInitState.Downloading ||
                    modelInitState.value == ModelInitState.Paused ||
                    modelInitState.value == ModelInitState.Finished)
            switchToClearing()
        }

        private fun switchToClearing() {
            if (modelInitState.value == ModelInitState.Paused) { modelInitState.value = ModelInitState.Clearing; clear() }
            else if (modelInitState.value == ModelInitState.Finished) {
                modelInitState.value = ModelInitState.Clearing
                if (chatState.modelName.value == modelConfig.modelId) chatState.requestTerminateChat { clear() }
                else clear()
            } else modelInitState.value = ModelInitState.Clearing
        }

        fun handleDelete() {
            require(modelInitState.value == ModelInitState.Downloading ||
                    modelInitState.value == ModelInitState.Paused ||
                    modelInitState.value == ModelInitState.Finished)
            switchToDeleting()
        }

        private fun switchToDeleting() {
            if (modelInitState.value == ModelInitState.Paused) { modelInitState.value = ModelInitState.Deleting; delete() }
            else if (modelInitState.value == ModelInitState.Finished) {
                modelInitState.value = ModelInitState.Deleting
                if (chatState.modelName.value == modelConfig.modelId) chatState.requestTerminateChat { delete() }
                else delete()
            } else modelInitState.value = ModelInitState.Deleting
        }

        private fun switchToIndexing() {
            modelInitState.value = ModelInitState.Indexing
            progress.value = 0
            total.value = modelConfig.tokenizerFiles.size + paramsConfig.paramsRecords.size
            for (tok in modelConfig.tokenizerFiles) {
                val f = File(modelDirFile, tok)
                if (f.exists()) ++progress.value
                else remainingTasks.add(DownloadTask(URL("${modelUrl}${ModelUrlSuffix}${tok}"), f))
            }
            for (rec in paramsConfig.paramsRecords) {
                val f = File(modelDirFile, rec.dataPath)
                if (f.exists()) ++progress.value
                else remainingTasks.add(DownloadTask(URL("${modelUrl}${ModelUrlSuffix}${rec.dataPath}"), f))
            }
            if (progress.value < total.value) switchToPaused() else switchToFinished()
        }

        private fun switchToDownloading() {
            modelInitState.value = ModelInitState.Downloading
            for (task in remainingTasks) {
                if (downloadingTasks.size < maxDownloadTasks) handleNewDownload(task) else return
            }
        }

        private fun handleNewDownload(task: DownloadTask) {
            downloadingTasks.add(task)
            thread(start = true) {
                val tempFile = File(modelDirFile, UUID.randomUUID().toString())
                task.url.openStream().use {
                    Channels.newChannel(it).use { src ->
                        FileOutputStream(tempFile).use { fo -> fo.channel.transferFrom(src, 0, Long.MAX_VALUE) }
                    }
                }
                tempFile.renameTo(task.file)
                viewModelScope.launch { handleFinishDownload(task) }
            }
        }

        private fun handleNextDownload() {
            for (task in remainingTasks) {
                if (!downloadingTasks.contains(task)) { handleNewDownload(task); break }
            }
        }

        private fun handleFinishDownload(task: DownloadTask) {
            remainingTasks.remove(task); downloadingTasks.remove(task); ++progress.value
            when (modelInitState.value) {
                ModelInitState.Downloading -> if (remainingTasks.isEmpty()) { if (downloadingTasks.isEmpty()) switchToFinished() } else handleNextDownload()
                ModelInitState.Pausing -> if (downloadingTasks.isEmpty()) switchToPaused()
                ModelInitState.Clearing -> if (downloadingTasks.isEmpty()) clear()
                ModelInitState.Deleting -> if (downloadingTasks.isEmpty()) delete()
                else -> {}
            }
        }

        private fun clear() {
            modelDirFile.listFiles { _, name -> name != ModelConfigFilename }?.forEach { it.deleteRecursively() }
            switchToIndexing()
        }

        private fun delete() {
            // Keep mlc-chat-config.json: the full engine config can't be reconstructed from the
            // in-memory ModelConfig subset, and downloadParamsConfig needs the dir to exist.
            // The card stays in the list and resets to an undownloaded state.
            modelDirFile.listFiles { _, name -> name != ModelConfigFilename }?.forEach { it.deleteRecursively() }
            progress.value = 0
            total.value = 1
            switchToInitializing()
        }
        private fun switchToPausing() { modelInitState.value = ModelInitState.Pausing }
        private fun switchToPaused() { modelInitState.value = ModelInitState.Paused }
        private fun switchToFinished() { modelInitState.value = ModelInitState.Finished }

        fun startChat() { chatState.requestReloadChat(modelConfig, modelDirFile.absolutePath) }

        fun estimatedVramGB(): String {
            val bytes = modelConfig.estimatedVramBytes ?: return "?"
            return String.format("%.1f", bytes.toFloat() / (1024 * 1024 * 1024))
        }

        fun fileSizeMB(): Long {
            return if (modelDirFile.exists())
                modelDirFile.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
            else 0L
        }
    }

    inner class ChatState {
        val messages = emptyList<MessageData>().toMutableStateList()
        val report = mutableStateOf("")
        val modelName = mutableStateOf("")
        private var modelChatState = mutableStateOf(ModelChatState.Ready)
            @Synchronized get
            @Synchronized set
        val engine = MLCEngine()
        private var historyMessages = mutableListOf<ChatCompletionMessage>()
        private var modelLib = ""
        private var modelPath = ""
        private val scope = CoroutineScope(Dispatchers.Main + Job())
        private var imageUri: Uri? = null

        private fun mainResetChat() {
            imageUri = null
            scope.launch(Dispatchers.IO) {
                callBackend { engine.reset() }
                withContext(Dispatchers.Main) {
                    historyMessages = mutableListOf()
                    clearHistory()
                    switchToReady()
                }
            }
        }

        private fun clearHistory() { messages.clear(); report.value = ""; historyMessages.clear() }
        private fun switchToResetting() { modelChatState.value = ModelChatState.Resetting }
        private fun switchToGenerating() { modelChatState.value = ModelChatState.Generating }
        private fun switchToReloading() { modelChatState.value = ModelChatState.Reloading }
        private fun switchToReady() { modelChatState.value = ModelChatState.Ready }
        private fun switchToFailed() { modelChatState.value = ModelChatState.Failed }
        private fun switchToTerminating() { modelChatState.value = ModelChatState.Terminating }

        private fun callBackend(cb: () -> Unit): Boolean {
            return try { cb(); true } catch (e: Exception) {
                scope.launch {
                    appendMessage(MessageRole.Assistant, "Error: ${e.localizedMessage}")
                    switchToFailed()
                }
                false
            }
        }

        fun requestResetChat() {
            require(interruptable())
            interruptChat(prologue = { switchToResetting() }, epilogue = { mainResetChat() })
        }

        private fun interruptChat(prologue: () -> Unit, epilogue: () -> Unit) {
            require(interruptable())
            if (modelChatState.value == ModelChatState.Ready) { prologue(); epilogue() }
            else if (modelChatState.value == ModelChatState.Generating) {
                prologue()
                scope.launch { epilogue() }
            }
        }

        fun requestTerminateChat(callback: () -> Unit) {
            require(interruptable())
            interruptChat(prologue = { switchToTerminating() }, epilogue = { mainTerminateChat(callback) })
        }

        private fun mainTerminateChat(callback: () -> Unit) {
            scope.launch(Dispatchers.IO) {
                callBackend { engine.unload() }
                withContext(Dispatchers.Main) {
                    clearHistory()
                    modelName.value = ""
                    modelLib = ""
                    modelPath = ""
                    switchToReady()
                    callback()
                }
            }
        }

        fun requestReloadChat(modelConfig: ModelConfig, modelPath: String) {
            if (this.modelName.value == modelConfig.modelId &&
                this.modelLib == modelConfig.modelLib &&
                this.modelPath == modelPath) return
            require(interruptable())
            interruptChat(prologue = { switchToReloading() }, epilogue = { mainReloadChat(modelConfig, modelPath) })
        }

        private fun mainReloadChat(modelConfig: ModelConfig, modelPath: String) {
            clearHistory()
            this.modelName.value = modelConfig.modelId
            this.modelLib = modelConfig.modelLib
            this.modelPath = modelPath
            scope.launch(Dispatchers.IO) {
                withContext(Dispatchers.Main) { Toast.makeText(application, "Loading model…", Toast.LENGTH_SHORT).show() }
                if (!callBackend { engine.unload(); engine.reload(modelPath, modelConfig.modelLib) }) return@launch
                withContext(Dispatchers.Main) { Toast.makeText(application, "Ready to chat", Toast.LENGTH_SHORT).show(); switchToReady() }
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
            // Capture URI reference on main thread — actual decode happens in executor below
            val capturedUri = imageUri
            imageUri = null
            val prefs = application.getSharedPreferences("mobileai", Context.MODE_PRIVATE)
            val maxTokens = prefs.getInt("max_tokens", 2048).coerceIn(256, 4096)
            val noThink = prefs.getBoolean("no_think", false)
            val thinkMaxTokens = prefs.getInt("think_max_tokens", 1024).coerceIn(256, 2048)
            scope.launch(Dispatchers.IO) {
                // Image decode runs on IO thread
                val content: ChatCompletionMessageContent = if (capturedUri != null) {
                    val bitmap = activity.contentResolver.openInputStream(capturedUri)?.use { i ->
                        BitmapFactory.decodeStream(i)
                    }
                    if (bitmap != null) {
                        val parts = listOf(
                            mapOf("type" to "text", "text" to prompt),
                            mapOf("type" to "image_url", "image_url" to bitmapToURL(bitmap))
                        )
                        ChatCompletionMessageContent(parts = parts)
                    } else {
                        ChatCompletionMessageContent(text = prompt)
                    }
                } else {
                    ChatCompletionMessageContent(text = prompt)
                }
                // Add to history on IO thread before launching inference — avoids race with Default thread
                historyMessages.add(ChatCompletionMessage(role = OpenAIProtocol.ChatCompletionRole.user, content = content))
                scope.launch(Dispatchers.Default) {
                    // Deprioritize inference so UI/system threads stay responsive
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    suspend fun runInference() {
                        // Qwen3 soft switch: a /no_think system message disables the <think> phase
                        val requestMessages = if (noThink)
                            mutableListOf(ChatCompletionMessage(role = OpenAIProtocol.ChatCompletionRole.system, content = "/no_think")).apply { addAll(historyMessages) }
                        else historyMessages
                        val responses = engine.chat.completions.create(
                            messages = requestMessages,
                            max_tokens = maxTokens,
                            stream_options = OpenAIProtocol.StreamOptions(include_usage = true)
                        )
                        var streaming = ""
                        var truncated = false
                        var completionTokenCount = 0
                        var firstTokenMs = 0L
                        val startMs = System.currentTimeMillis()
                        var lastUiUpdateMs = 0L
                        var thinkTokenCount = 0
                        var thinkBudgetInjected = false
                        var tokensSinceBreath = 0
                        for (res in responses) {
                            val ok = try {
                                for (choice in res.choices) {
                                    choice.delta.content?.let {
                                        if (firstTokenMs == 0L) firstTokenMs = System.currentTimeMillis() - startMs
                                        val token = it.asText()
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
                                    }
                                    if (choice.finish_reason == "length") truncated = true
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
                                val displayText = answer + if (truncated) " [truncated]" else ""
                                withContext(Dispatchers.Main) { updateMessage(MessageRole.Assistant, displayText, think, thinkOpen) }
                            }
                            res.usage?.let { u -> withContext(Dispatchers.Main) { report.value = u.extra?.asTextLabel() ?: "" } }
                            // Thermal-driven token pacing — use delay() to suspend, not Thread.sleep()
                            when {
                                this@AppViewModel.thermalGovernor.hardLimit -> delay(500)
                                this@AppViewModel.thermalGovernor.softLimit -> delay(150)
                                else -> delay(30)
                            }
                        }
                        // Final UI update — if think block never closed, force-close it
                        val streamingFinal = if (streaming.contains("<think>") && !streaming.contains("</think>"))
                            streaming + "\n[think exhausted — model produced no answer]\n</think>"
                        else streaming
                        val (thinkFinal, answerFinal, _) = parseThinkBlocks(streamingFinal)
                        val displayFinal = answerFinal + if (truncated) " [truncated]" else ""
                        withContext(Dispatchers.Main) { updateMessage(MessageRole.Assistant, displayFinal, thinkFinal, false) }
                        if (streamingFinal.isNotEmpty()) {
                            // Store only the answer in history — re-feeding <think> content wastes context
                            val (_, finalAnswer, _) = parseThinkBlocks(streamingFinal)
                            historyMessages.add(ChatCompletionMessage(role = OpenAIProtocol.ChatCompletionRole.assistant, content = finalAnswer.ifEmpty { streamingFinal }))
                        } else {
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
            val noThink = application.getSharedPreferences("mobileai", Context.MODE_PRIVATE)
                .getBoolean("no_think", false)
            val msgs = mutableListOf<ChatCompletionMessage>()
            if (noThink) msgs.add(ChatCompletionMessage(role = OpenAIProtocol.ChatCompletionRole.system, content = "/no_think"))
            msgs.add(ChatCompletionMessage(role = OpenAIProtocol.ChatCompletionRole.user, content = prompt))
            val result = StringBuilder()
            var firstTokenMs = 0L
            val startMs = System.currentTimeMillis()
            var completionTokenCount = 0
            var truncated = false
            var tokensSinceBreath = 0

            val responses = engine.chat.completions.create(
                messages = msgs,
                max_tokens = maxTokens,
                stream_options = OpenAIProtocol.StreamOptions(include_usage = false)
            )
            for (res in responses) {
                for (choice in res.choices) {
                    choice.delta.content?.let {
                        if (firstTokenMs == 0L) firstTokenMs = System.currentTimeMillis() - startMs
                        result.append(it.asText())
                        completionTokenCount++
                        tokensSinceBreath++
                    }
                    if (choice.finish_reason == "length") truncated = true
                }
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
            val totalMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1)
            val tps = completionTokenCount.toFloat() / (totalMs / 1000f).coerceAtLeast(0.001f)
            scope.launch {
                val entry = InferenceStats(
                    tokensPerSecond = tps,
                    promptTokens = msgs.sumOf { it.content.toString().length / 4 },
                    completionTokens = completionTokenCount,
                    timeToFirstTokenMs = firstTokenMs,
                    lastRequestMs = System.currentTimeMillis()
                )
                this@AppViewModel.inferenceStats.value = entry
                this@AppViewModel.inferenceHistory.add(0, entry)
                if (this@AppViewModel.inferenceHistory.size > 20)
                    this@AppViewModel.inferenceHistory.removeAt(this@AppViewModel.inferenceHistory.size - 1)
            }
            // " [truncated]" is a sentinel consumed by ApiServer to report finish_reason="length"
            return result.toString().trim() + if (truncated) " [truncated]" else ""
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
data class AppConfig(
    @SerializedName("model_libs") var modelLibs: MutableList<String>,
    @SerializedName("model_list") val modelList: MutableList<ModelRecord>
)
data class ModelRecord(
    @SerializedName("model_url") val modelUrl: String,
    @SerializedName("model_id") val modelId: String,
    @SerializedName("estimated_vram_bytes") val estimatedVramBytes: Long?,
    @SerializedName("model_lib") val modelLib: String
)
data class ModelConfig(
    @SerializedName("model_lib") var modelLib: String,
    @SerializedName("model_id") var modelId: String,
    @SerializedName("estimated_vram_bytes") var estimatedVramBytes: Long?,
    @SerializedName("tokenizer_files") val tokenizerFiles: List<String>,
    @SerializedName("context_window_size") val contextWindowSize: Int,
    @SerializedName("prefill_chunk_size") val prefillChunkSize: Int
)
data class ParamsRecord(@SerializedName("dataPath") val dataPath: String)
data class ParamsConfig(@SerializedName("records") val paramsRecords: List<ParamsRecord>)
data class InferenceStats(
    val tokensPerSecond: Float = 0f,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val timeToFirstTokenMs: Long = 0L,
    val lastRequestMs: Long = 0L
)
