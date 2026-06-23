package ai.mlc.mobileai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonPrimitive
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
data class LoadModelRequest(val model: String = "")
data class ErrorResponse(val error: String)

// OpenAI-compatible types
// reasoning_content carries <think> output, DeepSeek-style; null (omitted) when absent
//
// content accepts either a plain string or an OpenAI content-parts array
// (e.g. [{"type":"text","text":"..."}]) — the OpenAI SDK always sends the
// array form for user/assistant messages, so both shapes must parse.
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

// Parses OaiMessage.content as either a JSON string or an OpenAI content-parts
// array, concatenating the "text" field of any {"type":"text",...} parts.
private val oaiMessageDeserializer = JsonDeserializer<OaiMessage> { json, _, context ->
    val obj = json.asJsonObject
    val role = obj.get("role")?.asString ?: ""
    val reasoningContent = obj.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString
    val contentEl = obj.get("content")
    val content = when {
        contentEl == null || contentEl.isJsonNull -> ""
        contentEl.isJsonArray -> contentEl.asJsonArray.joinToString("") { part ->
            val partObj = part.asJsonObject
            val type = partObj.get("type")?.asString
            if (type == null || type == "text") partObj.get("text")?.asString ?: "" else ""
        }
        else -> contentEl.asString
    }
    OaiMessage(role = role, content = content, reasoning_content = reasoningContent)
}

class ApiServer(
    private val service: ForegroundInferenceService,
    private val port: Int = 8080
) {
    private val gson = Gson()
    private val oaiGson = GsonBuilder()
        .registerTypeAdapter(OaiMessage::class.java, oaiMessageDeserializer)
        .create()
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

                post("/v1/models/load") {
                    val body = call.receiveText()
                    val req = try { gson.fromJson(body, LoadModelRequest::class.java) } catch (e: Exception) { LoadModelRequest() }
                    if (req.model.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, gson.toJson(ErrorResponse("model is required")))
                        return@post
                    }
                    val resolved = service.resolveModelId(req.model)
                    if (resolved == null) {
                        call.respond(HttpStatusCode.NotFound, gson.toJson(ErrorResponse("model '${req.model}' not found or not downloaded")))
                        return@post
                    }
                    if (service.loadedModelName() == resolved && service.isModelLoaded()) {
                        call.respondText(gson.toJson(HealthResponse("ok", resolved, true)), ContentType.Application.Json)
                        return@post
                    }
                    val switched = service.switchModel(resolved)
                    if (!switched) {
                        call.respond(HttpStatusCode.NotFound, gson.toJson(ErrorResponse("model '${req.model}' not found or not downloaded")))
                        return@post
                    }
                    val ready = service.awaitModelReady(resolved, timeoutMs = 120_000L)
                    if (!ready) {
                        call.respond(HttpStatusCode.GatewayTimeout, gson.toJson(ErrorResponse("model '$resolved' did not finish loading within 120s")))
                        return@post
                    }
                    call.respondText(gson.toJson(HealthResponse("ok", resolved, true)), ContentType.Application.Json)
                }

                post("/v1/chat/completions") {
                    val body = call.receiveText()
                    val req = try { oaiGson.fromJson(body, OaiChatRequest::class.java) } catch (e: Exception) { OaiChatRequest() }
                    if (req.messages.isEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, gson.toJson(ErrorResponse("messages array is required")))
                        return@post
                    }
                    if (!req.model.isNullOrBlank()) {
                        val resolved = service.resolveModelId(req.model)
                        val currentlyLoaded = service.loadedModelName()
                        if (resolved == null) {
                            call.respond(HttpStatusCode.BadRequest, gson.toJson(ErrorResponse("model '${req.model}' not found or not downloaded")))
                            return@post
                        }
                        if (!service.isModelLoaded() || resolved != currentlyLoaded) {
                            call.respond(
                                HttpStatusCode.Conflict,
                                gson.toJson(ErrorResponse("model '${req.model}' is not loaded; currently loaded: '$currentlyLoaded'. Use POST /v1/models/load to switch."))
                            )
                            return@post
                        }
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
