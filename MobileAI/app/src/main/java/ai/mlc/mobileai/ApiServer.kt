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
    val max_tokens: Int = 512,
    val model: String? = null   // optional: "1.7b", "4b", or full model ID
)
data class ChatResponse(val response: String, val model: String)
data class HealthResponse(val status: String, val model: String, val loaded: Boolean)
data class ModelInfo(val id: String, val loaded: Boolean)

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
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 3000)
    }
}
