package com.kiosa.relay.server

import com.kiosa.relay.core.RelayEngine
import com.kiosa.relay.core.UpstreamClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.copyAndClose
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

private sealed class Attempt {
    object Success : Attempt()
    object RateLimited : Attempt()
    data class Failed(val msg: String) : Attempt()
}

/**
 * Embedded Ktor (CIO) server exposing:
 *   POST /v1/chat/completions   OpenAI passthrough (streaming)
 *   POST /v1/completions        OpenAI passthrough
 *   POST /v1/embeddings         OpenAI passthrough
 *   POST /v1/messages           Anthropic Messages -> OpenAI bridge (streaming SSE)
 *   GET  /v1/models             model list passthrough (not counted against quota)
 *   GET  /relay/status          live JSON status of keys/stats
 *   GET  /                      info
 */
class RelayServer(
    private val engine: RelayEngine,
    private val settings: () -> com.kiosa.relay.store.RelaySettings,
) {
    private val serverRef = AtomicReference<ApplicationEngine?>(null)
    private val client = UpstreamClient()

    fun start(host: String, port: Int) {
        check(serverRef.get() == null) { "server already running" }
        val srv = embeddedServer(CIO, host = host, port = port) { module() }
        serverRef.set(srv)
        srv.start(wait = false)
    }

    fun stop() {
        serverRef.getAndSet(null)?.stop(500, 1500)
        client.close()
    }

    fun isRunning(): Boolean = serverRef.get() != null

    private fun Application.module() {
        intercept(ApplicationCallPipeline.Plugins) {
            val s = settings()
            if (s.requireAccessKey && call.request.path() != "/") {
                val auth = call.request.headers["Authorization"]
                val xk = call.request.headers["x-api-key"]
                val ok = (auth != null && auth == "Bearer ${s.accessKey}") || (xk != null && xk == s.accessKey)
                if (!ok) {
                    call.err(HttpStatusCode.Unauthorized, "Invalid or missing relay access key")
                    finish()
                }
            }
        }
        routing {
            get("/") { call.respondText(infoJson(), ContentType.Application.Json) }
            get("/relay/status") { call.respondText(statusJson(), ContentType.Application.Json) }
            get("/v1/models") { call.modelsRoute() }
            post("/v1/chat/completions") { call.relayPassthrough("/chat/completions") }
            post("/v1/completions") { call.relayPassthrough("/completions") }
            post("/v1/embeddings") { call.relayPassthrough("/embeddings") }
            post("/v1/messages") { call.anthropicRoute() }
        }
    }

    // ------------------------- core attempt loop -------------------------

    private suspend fun ApplicationCall.attemptLoop(label: String, attempt: suspend (RelayEngine.Slot) -> Attempt): Boolean {
        val s = settings()
        var attempts = 0
        var lastErr: String? = null
        while (attempts++ < RelayEngine.MAX_ATTEMPTS) {
            if (settings().paused) {
                err(HttpStatusCode.ServiceUnavailable, "Relay is paused")
                return false
            }
            val slot = engine.pickAvailable()
            if (slot == null) {
                val soon = engine.soonestRevival()
                val now = System.currentTimeMillis()
                val waitMs = soon?.minus(now) ?: -1L
                if (soon == null || waitMs > s.maxWaitSec * 1000L) {
                    val retry = ((soon?.minus(now) ?: (s.cooldownSec * 1000L)) / 1000L).toInt().coerceAtLeast(1)
                    engine.markReject(retry.toLong())
                    response.headers.append("Retry-After", retry.toString())
                    err(HttpStatusCode.TooManyRequests, "All API keys are rate-limited. Retry after ${retry}s.")
                    return false
                }
                engine.markWait(waitMs)
                delay(waitMs + 75L)
                continue
            }
            when (val r = attempt(slot)) {
                is Attempt.Success -> return true
                is Attempt.RateLimited -> lastErr = "429 rate limited"
                is Attempt.Failed -> lastErr = r.msg
            }
        }
        err(HttpStatusCode.BadGateway, "All keys failed after ${RelayEngine.MAX_ATTEMPTS} attempts: ${lastErr ?: "unknown"}")
        return false
    }

    // ------------------------------ routes ------------------------------

    private suspend fun ApplicationCall.relayPassthrough(upstreamPath: String) {
        val s = settings()
        if (s.paused) {
            err(HttpStatusCode.ServiceUnavailable, "Relay is paused")
            return
        }
        val body = receive<ByteArray>()
        val label = request.path()
        attemptLoop(label) { slot ->
            try {
                client.post(
                    url = s.upstreamBaseUrl.trimEnd('/') + upstreamPath,
                    body = body,
                    secret = slot.apiKey.secret,
                    socketTimeoutMs = s.requestTimeoutSec * 1000L,
                ) { resp ->
                    val code = resp.status.value
                    when {
                        code == 429 -> {
                            val t = resp.bodyAsTextSafe()
                            engine.markRateLimited(slot, t, s.cooldownSec.toLong())
                            Attempt.RateLimited
                        }
                        code >= 400 -> {
                            val t = resp.bodyAsTextSafe()
                            val cd = if (code == 401 || code == 403) s.authCooldownSec.toLong() else s.errorCooldownSec.toLong()
                            engine.markError(slot, "HTTP $code: $t", cd)
                            Attempt.Failed("HTTP $code")
                        }
                        else -> {
                            engine.markSuccess(slot, "$label 200 via ${slot.apiKey.label}")
                            response.headers.append("X-Kiosa-Upstream", slot.apiKey.label)
                            respondBytesWriter(
                                contentType = resp.contentType() ?: ContentType.Application.Json,
                                status = resp.status,
                            ) {
                                resp.bodyAsChannel().copyAndClose(this)
                            }
                            Attempt.Success
                        }
                    }
                }
            } catch (e: Exception) {
                engine.markError(slot, e.message ?: "upstream error", s.errorCooldownSec.toLong())
                Attempt.Failed(e.message ?: "upstream error")
            }
        }
    }

    private suspend fun ApplicationCall.anthropicRoute() {
        val s = settings()
        if (s.paused) {
            err(HttpStatusCode.ServiceUnavailable, "Relay is paused")
            return
        }
        val raw = receiveText()
        val a = try {
            JSONObject(raw)
        } catch (e: Exception) {
            err(HttpStatusCode.BadRequest, "invalid JSON: ${e.message}")
            return
        }
        val openaiBody = AnthropicBridge.translateRequest(a).toString().encodeToByteArray()
        val model = a.optString("model", "")
        val stream = a.optBoolean("stream", false)
        attemptLoop("/v1/messages") { slot ->
            try {
                client.post(
                    url = s.upstreamBaseUrl.trimEnd('/') + "/chat/completions",
                    body = openaiBody,
                    secret = slot.apiKey.secret,
                    socketTimeoutMs = s.requestTimeoutSec * 1000L,
                ) { resp ->
                    val code = resp.status.value
                    when {
                        code == 429 -> {
                            val t = resp.bodyAsTextSafe()
                            engine.markRateLimited(slot, t, s.cooldownSec.toLong())
                            Attempt.RateLimited
                        }
                        code >= 400 -> {
                            val t = resp.bodyAsTextSafe()
                            val cd = if (code == 401 || code == 403) s.authCooldownSec.toLong() else s.errorCooldownSec.toLong()
                            engine.markError(slot, "HTTP $code: $t", cd)
                            Attempt.Failed("HTTP $code")
                        }
                        else -> {
                            engine.markSuccess(slot, "/v1/messages 200 via ${slot.apiKey.label}")
                            if (stream) {
                                respondBytesWriter(contentType = ContentType.Text.EventStream) {
                                    AnthropicBridge.streamToAnthropic(resp.bodyAsChannel(), model) { ev ->
                                        writeFully(ev.encodeToByteArray())
                                        flush()
                                    }
                                }
                            } else {
                                val text = resp.bodyAsTextSafe()
                                respondText(AnthropicBridge.responseToAnthropic(text, model), ContentType.Application.Json, resp.status)
                            }
                            Attempt.Success
                        }
                    }
                }
            } catch (e: Exception) {
                engine.markError(slot, e.message ?: "upstream error", s.errorCooldownSec.toLong())
                Attempt.Failed(e.message ?: "upstream error")
            }
        }
    }

    private suspend fun ApplicationCall.modelsRoute() {
        val s = settings()
        val secret = engine.firstEnabledSecret()
        if (secret == null) {
            err(HttpStatusCode.ServiceUnavailable, "No API keys configured")
            return
        }
        try {
            val resp = client.getText(s.upstreamBaseUrl.trimEnd('/') + "/models", secret, 30_000)
            val text = resp.bodyAsTextSafe(2 * 1024 * 1024)
            respondText(text, resp.contentType() ?: ContentType.Application.Json, resp.status)
        } catch (e: Exception) {
            err(HttpStatusCode.BadGateway, "models fetch failed: ${e.message}")
        }
    }

    // ------------------------------ helpers ------------------------------

    private fun infoJson(): String =
        JSONObject()
            .put("app", "Kiosa Relay")
            .put("version", "1.0.0")
            .put(
                "endpoints",
                JSONArray()
                    .put("POST /v1/chat/completions")
                    .put("POST /v1/completions")
                    .put("POST /v1/embeddings")
                    .put("POST /v1/messages  (Anthropic-compatible)")
                    .put("GET /v1/models")
                    .put("GET /relay/status"),
            )
            .put("strategy", "sequential exhaustion + per-key cooldown (no round-robin)")
            .toString()

    private fun statusJson(): String {
        val snap = engine.snapshot.value
        val s = settings()
        val keys = JSONArray()
        snap.keys.forEach { k ->
            keys.put(
                JSONObject()
                    .put("label", k.label)
                    .put("status", k.status.name.lowercase())
                    .put("cooldownRemainingSec", ((k.cooldownUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0))
                    .put("success", k.success)
                    .put("rateLimited", k.rateLimited)
                    .put("fail", k.fail)
                    .put("lastError", k.lastError ?: JSONObject.NULL),
            )
        }
        val log = JSONArray()
        snap.log.take(50).forEach { l ->
            log.put(
                JSONObject()
                    .put("t", l.t)
                    .put("kind", l.kind)
                    .put("key", l.key)
                    .put("msg", l.msg),
            )
        }
        return JSONObject()
            .put("app", "Kiosa Relay")
            .put("version", "1.0.0")
            .put("strategy", "sequential exhaustion + per-key cooldown (no round-robin)")
            .put("upstream", s.upstreamBaseUrl)
            .put("paused", s.paused)
            .put(
                "stats",
                JSONObject()
                    .put("relayed", snap.stats.relayed)
                    .put("rateLimited", snap.stats.rateLimited)
                    .put("errors", snap.stats.errors)
                    .put("waits", snap.stats.waits),
            )
            .put("keys", keys)
            .put("log", log)
            .toString()
    }

    private suspend fun ApplicationCall.err(status: HttpStatusCode, message: String) {
        respondText(
            JSONObject().put("error", JSONObject().put("message", message).put("type", "kiosa_relay")).toString(),
            ContentType.Application.Json,
            status,
        )
    }

    private suspend fun HttpResponse.bodyAsTextSafe(limit: Int = 512 * 1024): String =
        try {
            bodyAsText().take(limit)
        } catch (e: Exception) {
            ""
        }
}
