package ai.mlc.mobileai

import android.content.Context
import com.google.gson.Gson
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.server.plugins.cors.routing.*
import kotlinx.coroutines.runBlocking

data class ChatRequest(
    val prompt: String = "",
    val max_tokens: Int = 2048,
    val model: String? = null
)
data class ChatResponse(val response: String, val model: String)
data class HealthResponse(val status: String, val model: String, val loaded: Boolean)
data class ModelInfo(val id: String, val loaded: Boolean)

// OpenAI-compatible types
// reasoning_content carries <think> output, DeepSeek-style; null (omitted) when absent
data class OaiMessage(val role: String, val content: String, val reasoning_content: String? = null)
data class OaiChatRequest(
    val model: String? = null,
    val messages: List<OaiMessage> = emptyList(),
    val max_tokens: Int = 2048,
    val stream: Boolean = false
)
data class OaiChoice(val index: Int, val message: OaiMessage, val finish_reason: String)
data class OaiUsage(val prompt_tokens: Int, val completion_tokens: Int, val total_tokens: Int)
data class OaiChatResponse(
    val id: String,
    val `object`: String = "chat.completion",
    val model: String,
    val choices: List<OaiChoice>,
    val usage: OaiUsage
)
data class OaiModelEntry(val id: String, val `object`: String = "model")
data class OaiModelList(val `object`: String = "list", val data: List<OaiModelEntry>)

class ApiServer(
    private val service: ForegroundInferenceService,
    private val port: Int = 8080
) {
    private val gson = Gson()
    private var server: ApplicationEngine? = null

    suspend fun start() {
        server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
            }
            routing {
                get("/health") {
                    val resp = HealthResponse(
                        status = "ok",
                        model = service.loadedModelName(),
                        loaded = service.isModelLoaded()
                    )
                    call.respondText(gson.toJson(resp), ContentType.Application.Json)
                }

                get("/models") {
                    val vm = service.appViewModel
                    val models = if (vm != null) {
                        vm.modelList.map { m ->
                            ModelInfo(
                                id = m.modelConfig.modelId,
                                loaded = service.loadedModelName() == m.modelConfig.modelId
                            )
                        }
                    } else {
                        listOf(ModelInfo(id = service.loadedModelName(), loaded = service.isModelLoaded()))
                    }
                    call.respondText(gson.toJson(models), ContentType.Application.Json)
                }

                post("/chat") {
                    val body = call.receiveText()
                    val req = try { gson.fromJson(body, ChatRequest::class.java) } catch (e: Exception) { ChatRequest() }
                    if (req.prompt.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, "Missing prompt")
                        return@post
                    }
                    val response = service.generateBlocking(req.prompt, req.max_tokens, req.model)
                    val resp = ChatResponse(response = response, model = service.loadedModelName())
                    call.respondText(gson.toJson(resp), ContentType.Application.Json)
                }

                post("/generate") {
                    val body = call.receiveText()
                    val req = try { gson.fromJson(body, ChatRequest::class.java) } catch (e: Exception) { ChatRequest() }
                    if (req.prompt.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, "Missing prompt")
                        return@post
                    }
                    val response = service.generateBlocking(req.prompt, req.max_tokens, req.model)
                    val resp = ChatResponse(response = response, model = service.loadedModelName())
                    call.respondText(gson.toJson(resp), ContentType.Application.Json)
                }

                get("/v1/models") {
                    val vm = service.appViewModel
                    val models = if (vm != null) {
                        vm.modelList.map { OaiModelEntry(id = it.modelConfig.modelId) }
                    } else {
                        listOf(OaiModelEntry(id = service.loadedModelName()))
                    }
                    call.respondText(gson.toJson(OaiModelList(data = models)), ContentType.Application.Json)
                }

                post("/v1/chat/completions") {
                    val body = call.receiveText()
                    val req = try { gson.fromJson(body, OaiChatRequest::class.java) } catch (e: Exception) { OaiChatRequest() }
                    if (req.messages.isEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, "messages array is required")
                        return@post
                    }
                    val prompt = req.messages.joinToString("\n") { msg ->
                        when (msg.role) {
                            "system" -> "System: ${msg.content}"
                            "user" -> "User: ${msg.content}"
                            "assistant" -> "Assistant: ${msg.content}"
                            else -> msg.content
                        }
                    } + "\nAssistant:"

                    val raw = service.generateBlocking(prompt, req.max_tokens.coerceAtMost(4096), req.model)
                    // generateResponse appends a " [truncated]" sentinel on finish_reason=="length"
                    val truncated = raw.trimEnd().endsWith("[truncated]")
                    val cleaned = if (truncated) raw.trimEnd().removeSuffix("[truncated]").trimEnd() else raw
                    val (think, answer, _) = parseThinkBlocks(cleaned)
                    val modelName = service.loadedModelName()
                    val promptTokens = prompt.length / 4
                    val completionTokens = cleaned.length / 4

                    val oaiResp = OaiChatResponse(
                        id = "chatcmpl-${java.util.UUID.randomUUID()}",
                        model = modelName,
                        choices = listOf(
                            OaiChoice(
                                index = 0,
                                message = OaiMessage(
                                    role = "assistant",
                                    content = answer,
                                    reasoning_content = think.ifEmpty { null }
                                ),
                                finish_reason = if (truncated) "length" else "stop"
                            )
                        ),
                        usage = OaiUsage(
                            prompt_tokens = promptTokens,
                            completion_tokens = completionTokens,
                            total_tokens = promptTokens + completionTokens
                        )
                    )
                    call.respondText(gson.toJson(oaiResp), ContentType.Application.Json)
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 3000)
    }
}
