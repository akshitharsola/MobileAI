// Based on MLCChat from MLC-LLM (https://github.com/mlc-ai/mlc-llm), Apache 2.0 License
// Extended by Akshit Harsola — adds generateResponse(), RAM tracking, service bridge
package ai.mlc.mobileai

import ai.mlc.mlcllm.MLCEngine
import ai.mlc.mlcllm.OpenAIProtocol
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
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.channels.Channels
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import ai.mlc.mlcllm.OpenAIProtocol.ChatCompletionMessage
import ai.mlc.mlcllm.OpenAIProtocol.ChatCompletionMessageContent
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

    val ramUsageMB = mutableStateOf(0L)
    val totalRamMB = mutableStateOf(0L)

    companion object {
        const val AppConfigFilename = "mlc-app-config.json"
        const val ModelConfigFilename = "mlc-chat-config.json"
        const val ParamsConfigFilename = "tensor-cache.json"
        const val ModelUrlSuffix = "resolve/main/"
    }

    init {
        loadAppConfig()
        updateRamUsage()
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

    fun requestDeleteModel(modelId: String) {
        deleteModel(modelId)
        issueAlert("Model: $modelId has been deleted")
    }

    private fun loadAppConfig() {
        // Always use the bundled assets config as source of truth.
        // Delete any stale external config that may survive an over-install.
        val appConfigFile = File(appDirFile, AppConfigFilename)
        appConfigFile.delete()
        val bundledJson = application.assets.open(AppConfigFilename).bufferedReader().use { it.readText() }
        val jsonString = bundledJson
        appConfig = gson.fromJson(jsonString, AppConfig::class.java)
        appConfig.modelLibs = emptyList<String>().toMutableList()
        modelList.clear()
        modelIdSet.clear()
        modelSampleList.clear()
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

    private fun deleteModel(modelId: String) {
        File(appDirFile, modelId).deleteRecursively()
        modelIdSet.remove(modelId)
        modelList.removeIf { it.modelConfig.modelId == modelId }
        updateAppConfig { appConfig.modelList.removeIf { it.modelId == modelId } }
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
        private val modelDirFile: File
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

        private fun delete() { modelDirFile.deleteRecursively(); requestDeleteModel(modelConfig.modelId) }
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
        private val executorService = Executors.newSingleThreadExecutor()
        private val scope = CoroutineScope(Dispatchers.Main + Job())
        private var imageUri: Uri? = null

        private fun mainResetChat() {
            imageUri = null
            executorService.submit {
                callBackend { engine.reset() }
                historyMessages = mutableListOf()
                scope.launch { clearHistory(); switchToReady() }
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
                executorService.submit { scope.launch { epilogue() } }
            }
        }

        fun requestTerminateChat(callback: () -> Unit) {
            require(interruptable())
            interruptChat(prologue = { switchToTerminating() }, epilogue = { mainTerminateChat(callback) })
        }

        private fun mainTerminateChat(callback: () -> Unit) {
            executorService.submit {
                callBackend { engine.unload() }
                scope.launch { clearHistory(); switchToReady(); callback() }
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
            executorService.submit {
                scope.launch { Toast.makeText(application, "Loading model…", Toast.LENGTH_SHORT).show() }
                if (!callBackend { engine.unload(); engine.reload(modelPath, modelConfig.modelLib) }) return@submit
                scope.launch { Toast.makeText(application, "Ready to chat", Toast.LENGTH_SHORT).show(); switchToReady() }
            }
        }

        fun requestImageBitmap(uri: Uri?) {
            require(chatable())
            switchToGenerating()
            executorService.submit {
                imageUri = uri
                scope.launch { report.value = "Image ready, ask your question."; if (modelChatState.value == ModelChatState.Generating) switchToReady() }
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
            var content = ChatCompletionMessageContent(text = prompt)
            if (imageUri != null) {
                val uri = imageUri
                val bitmap = uri?.let { activity.contentResolver.openInputStream(it)?.use { i -> BitmapFactory.decodeStream(i) } }
                if (bitmap != null) {
                    val parts = listOf(
                        mapOf("type" to "text", "text" to prompt),
                        mapOf("type" to "image_url", "image_url" to bitmapToURL(bitmap))
                    )
                    content = ChatCompletionMessageContent(parts = parts)
                }
                imageUri = null
            }
            executorService.submit {
                historyMessages.add(ChatCompletionMessage(role = OpenAIProtocol.ChatCompletionRole.user, content = content))
                scope.launch {
                    val responses = engine.chat.completions.create(
                        messages = historyMessages,
                        stream_options = OpenAIProtocol.StreamOptions(include_usage = true)
                    )
                    var streaming = ""
                    var truncated = false
                    for (res in responses) {
                        if (!callBackend {
                            for (choice in res.choices) {
                                choice.delta.content?.let { streaming += it.asText() }
                                if (choice.finish_reason == "length") truncated = true
                            }
                            updateMessage(MessageRole.Assistant, streaming)
                            res.usage?.let { report.value = it.extra?.asTextLabel() ?: "" }
                            if (truncated) updateMessage(MessageRole.Assistant, "$streaming [truncated]")
                        }) return@launch
                    }
                    if (streaming.isNotEmpty()) {
                        historyMessages.add(ChatCompletionMessage(role = OpenAIProtocol.ChatCompletionRole.assistant, content = streaming))
                    } else {
                        if (historyMessages.isNotEmpty()) historyMessages.removeAt(historyMessages.size - 1)
                    }
                    if (modelChatState.value == ModelChatState.Generating) switchToReady()
                }
            }
        }

        // Used by ForegroundInferenceService for headless inference
        suspend fun generateResponse(prompt: String, maxTokens: Int = 512): String {
            if (!chatable()) return "Model not ready"
            val messages = listOf(ChatCompletionMessage(role = OpenAIProtocol.ChatCompletionRole.user, content = prompt))
            val result = StringBuilder()
            val responses = engine.chat.completions.create(
                messages = messages,
                max_tokens = maxTokens,
                stream_options = OpenAIProtocol.StreamOptions(include_usage = false)
            )
            for (res in responses) {
                for (choice in res.choices) {
                    choice.delta.content?.let { result.append(it.asText()) }
                }
            }
            return result.toString().trim()
        }

        private fun appendMessage(role: MessageRole, text: String) { messages.add(MessageData(role, text)) }
        private fun updateMessage(role: MessageRole, text: String) { messages[messages.size - 1] = MessageData(role, text) }
        fun chatable(): Boolean = modelChatState.value == ModelChatState.Ready
        fun interruptable(): Boolean = modelChatState.value == ModelChatState.Ready ||
                modelChatState.value == ModelChatState.Generating ||
                modelChatState.value == ModelChatState.Failed
    }
}

enum class ModelInitState { Initializing, Indexing, Paused, Downloading, Pausing, Clearing, Deleting, Finished }
enum class ModelChatState { Generating, Resetting, Reloading, Terminating, Ready, Failed }
enum class MessageRole { Assistant, User }

data class DownloadTask(val url: URL, val file: File)
data class MessageData(val role: MessageRole, val text: String, val id: UUID = UUID.randomUUID(), var imageUri: Uri? = null)
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
