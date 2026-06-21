package ai.mlc.mobileai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.File

class LiteRtLmEngine(private val context: Context) {

    private var engine: Engine? = null
    private var _modelId: String = ""

    val isLoaded: Boolean
        get() = engine != null

    val modelId: String
        get() = _modelId

    fun load(modelId: String, modelFilePath: String, backend: String = "gpu") {
        // Unload any existing engine first
        unload()

        val selectedBackend = when (backend.lowercase()) {
            "cpu" -> Backend.CPU()
            "npu" -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
            else -> Backend.GPU()
        }

        val engineConfig = EngineConfig(
            modelPath = modelFilePath,
            backend = selectedBackend,
            cacheDir = context.cacheDir.absolutePath
        )

        val newEngine = Engine(engineConfig)
        newEngine.initialize()

        engine = newEngine
        _modelId = modelId
    }

    fun unload() {
        engine?.close()
        engine = null
        _modelId = ""
    }

    fun streamResponse(
        prompt: String,
        systemPrompt: String? = null,
        history: List<Pair<String, String>> = emptyList(),
        maxTokens: Int = 2048
    ): Flow<String> = flow {
        val currentEngine = engine ?: error("Engine not loaded. Call load() first.")

        val initialMessages = history.flatMap { (role, content) ->
            when (role.lowercase()) {
                "user" -> listOf(Message.user(content))
                "assistant", "model" -> listOf(Message.model(content))
                else -> emptyList()
            }
        }

        val conversationConfig = ConversationConfig(
            systemInstruction = systemPrompt?.let { Contents.of(Content.Text(it)) },
            initialMessages = initialMessages,
            samplerConfig = SamplerConfig(topK = 10, topP = 0.95, temperature = 0.8)
        )

        val conversation = currentEngine.createConversation(conversationConfig)
        try {
            conversation.sendMessageAsync(prompt).collect { message ->
                val text = message.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
                if (text.isNotEmpty()) {
                    emit(text)
                }
            }
        } finally {
            conversation.close()
        }
    }

    companion object {
        fun modelFilePath(context: Context, filename: String): String =
            File(context.getExternalFilesDir(""), filename).absolutePath

        fun modelFileExists(context: Context, filename: String): Boolean =
            File(context.getExternalFilesDir(""), filename).exists()

        fun modelFileSizeMB(context: Context, filename: String): Long {
            val file = File(context.getExternalFilesDir(""), filename)
            return if (file.exists()) file.length() / (1024L * 1024L) else 0L
        }
    }
}

data class LocalisModelRecord(
    val model_id: String,
    val display_name: String,
    val filename: String,
    val estimated_size_bytes: Long,
    val backend: String = "gpu",
    val hf_repo: String = model_id,
    val hf_org: String = "litert-community"
)

data class LocalisModelConfig(val model_list: List<LocalisModelRecord>)
