package com.kiosa.relay.server

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Translates Anthropic Messages API (/v1/messages) <-> OpenAI Chat Completions,
 * including SSE streaming. Logic ported 1:1 from the tested Node prototype
 * (mock tests 1 & 2 passed; see proto/TEST-RESULTS.md).
 */
object AnthropicBridge {

    private val STOP_MAP = mapOf(
        "tool_calls" to "tool_use",
        "length" to "max_tokens",
        "stop" to "end_turn",
        "content_filter" to "end_turn",
    )

    // ---------------- request: anthropic -> openai ----------------

    fun translateRequest(a: JSONObject): JSONObject {
        val o = JSONObject()
        o.put("model", a.optString("model", ""))
        o.put("max_tokens", if (a.has("max_tokens") && !a.isNull("max_tokens")) a.getLong("max_tokens") else 4096L)
        if (a.has("temperature") && !a.isNull("temperature")) o.put("temperature", a.getDouble("temperature"))
        if (a.has("top_p") && !a.isNull("top_p")) o.put("top_p", a.getDouble("top_p"))
        if (a.has("stop_sequences") && !a.isNull("stop_sequences")) o.put("stop", a.getJSONArray("stop_sequences"))
        if (a.has("stream") && !a.isNull("stream")) o.put("stream", a.getBoolean("stream"))

        val tools = a.optJSONArray("tools")
        if (tools != null && tools.length() > 0) {
            val t = JSONArray()
            for (i in 0 until tools.length()) {
                val tool = tools.optJSONObject(i) ?: continue
                t.put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", tool.optString("name", ""))
                                .put("description", tool.optString("description", ""))
                                .put(
                                    "parameters",
                                    tool.optJSONObject("input_schema")
                                        ?: JSONObject().put("type", "object").put("properties", JSONObject()),
                                ),
                        ),
                )
            }
            o.put("tools", t)
        }

        val tc = a.optJSONObject("tool_choice")
        if (tc != null) {
            when (tc.optString("type")) {
                "auto" -> o.put("tool_choice", "auto")
                "any" -> o.put("tool_choice", "required")
                "none" -> o.put("tool_choice", "none")
                "tool" -> o.put(
                    "tool_choice",
                    JSONObject().put("type", "function").put("function", JSONObject().put("name", tc.optString("name", ""))),
                )
            }
        }

        val messages = JSONArray()

        val system = a.opt("system")
        if (system != null && !JSONObject.NULL.equals(system)) {
            val s = when (system) {
                is JSONArray -> (0 until system.length()).joinToString("\n") { i -> system.optJSONObject(i)?.optString("text", "") ?: "" }
                else -> system.toString()
            }
            if (s.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", s))
        }

        val msgs = a.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            val content = m.opt("content")
            if (content == null || JSONObject.NULL.equals(content)) continue

            if (content is String) {
                messages.put(JSONObject().put("role", role).put("content", content))
                continue
            }
            val blocks = content as? JSONArray ?: continue

            val textParts = mutableListOf<String>()
            val toolCalls = JSONArray()
            val toolResults = mutableListOf<JSONObject>()
            val images = mutableListOf<JSONObject>()

            for (bIdx in 0 until blocks.length()) {
                val b = blocks.optJSONObject(bIdx) ?: continue
                when (b.optString("type")) {
                    "text" -> textParts.add(b.optString("text", ""))
                    "thinking", "redacted_thinking" -> { /* dropped */ }
                    "image" -> {
                        val src = b.optJSONObject("source")
                        val url = when (src?.optString("type")) {
                            "base64" -> "data:${src.optString("media_type")};base64,${src.optString("data")}"
                            else -> src?.optString("url") ?: ""
                        }
                        if (url.isNotBlank()) {
                            images.add(
                                JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)),
                            )
                        }
                    }
                    "tool_use" -> toolCalls.put(
                        JSONObject()
                            .put("id", b.optString("id", "call_${UUID.randomUUID()}"))
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", b.optString("name", ""))
                                    .put("arguments", (b.optJSONObject("input") ?: JSONObject()).toString()),
                            ),
                    )
                    "tool_result" -> {
                        val c = b.opt("content")
                        val text = when (c) {
                            is String -> c
                            is JSONArray -> (0 until c.length()).joinToString("\n") { j -> c.optJSONObject(j)?.optString("text", "") ?: "" }
                            else -> ""
                        }
                        toolResults.add(
                            JSONObject()
                                .put("role", "tool")
                                .put("tool_call_id", b.optString("tool_use_id", ""))
                                .put("content", text),
                        )
                    }
                }
            }

            toolResults.forEach { messages.put(it) }

            if (images.isNotEmpty() || textParts.isNotEmpty() || toolCalls.length() > 0) {
                val msg = JSONObject().put("role", role)
                if (images.isNotEmpty()) {
                    val arr = JSONArray()
                    if (textParts.isNotEmpty()) arr.put(JSONObject().put("type", "text").put("text", textParts.joinToString("")))
                    images.forEach { arr.put(it) }
                    msg.put("content", arr)
                } else {
                    val joined = textParts.joinToString("")
                    msg.put("content", if (joined.isEmpty() && toolCalls.length() > 0) JSONObject.NULL else joined)
                    if (toolCalls.length() > 0) msg.put("tool_calls", toolCalls)
                }
                messages.put(msg)
            }
        }

        o.put("messages", messages)
        return o
    }

    // ---------------- response: openai -> anthropic (non-stream) ----------------

    fun responseToAnthropic(openaiText: String, model: String?): String {
        val j = try {
            JSONObject(openaiText)
        } catch (e: Exception) {
            return errorJson("bad upstream json")
        }
        val err = j.optJSONObject("error")
        if (err != null) return errorJson(err.optString("message", "upstream error"))

        val choice = j.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message") ?: JSONObject()
        val content = JSONArray()

        val reasoning = message.optString("reasoning_content", "")
        if (reasoning.isNotBlank()) {
            content.put(JSONObject().put("type", "thinking").put("thinking", reasoning).put("signature", ""))
        }
        val c = message.opt("content")
        when (c) {
            is String -> if (c.isNotEmpty()) content.put(JSONObject().put("type", "text").put("text", c))
            is JSONArray -> {
                for (i in 0 until c.length()) {
                    val p = c.optJSONObject(i) ?: continue
                    if (p.optString("type") == "text" && p.optString("text").isNotEmpty()) {
                        content.put(JSONObject().put("type", "text").put("text", p.optString("text")))
                    }
                }
            }
        }
        val toolCalls = message.optJSONArray("tool_calls") ?: JSONArray()
        for (i in 0 until toolCalls.length()) {
            val tc = toolCalls.optJSONObject(i) ?: continue
            val fn = tc.optJSONObject("function") ?: JSONObject()
            val input = try {
                JSONObject(fn.optString("arguments", "{}").ifBlank { "{}" })
            } catch (e: Exception) {
                JSONObject()
            }
            content.put(
                JSONObject()
                    .put("type", "tool_use")
                    .put("id", tc.optString("id", "call_${UUID.randomUUID()}"))
                    .put("name", fn.optString("name", ""))
                    .put("input", input),
            )
        }

        val usage = j.optJSONObject("usage")
        val out = JSONObject()
            .put("id", "msg_" + j.optString("id", UUID.randomUUID().toString()))
            .put("type", "message")
            .put("role", "assistant")
            .put("model", if (model.isNullOrBlank()) j.optString("model", "") else model)
            .put("content", content)
            .put("stop_reason", STOP_MAP[choice?.optString("finish_reason", "")] ?: "end_turn")
            .put("stop_sequence", JSONObject.NULL)
            .put(
                "usage",
                JSONObject()
                    .put("input_tokens", usage?.optLong("prompt_tokens", 0) ?: 0)
                    .put("output_tokens", usage?.optLong("completion_tokens", 0) ?: 0),
            )
        return out.toString()
    }

    // ---------------- stream: openai SSE -> anthropic SSE ----------------

    suspend fun streamToAnthropic(
        channel: ByteReadChannel,
        model: String?,
        emit: suspend (String) -> Unit,
    ) {
        var nextIdx = 0
        var textOpen = -1
        var thinkOpen = -1
        val toolBlocks = HashMap<Int, Int>() // openai tool index -> anthropic block index
        var inTokens = 0L
        var outTokens = 0L
        var outChars = 0L
        var stopReason: String? = null
        var sawEvent = false

        fun event(type: String, data: JSONObject): String =
            "event: $type\ndata: $data\n\n"

        emit(
            event(
                "message_start",
                JSONObject().put(
                    "message",
                    JSONObject()
                        .put("id", "msg_${UUID.randomUUID()}")
                        .put("type", "message")
                        .put("role", "assistant")
                        .put("model", model ?: "")
                        .put("content", JSONArray())
                        .put("stop_sequence", JSONObject.NULL)
                        .put("stop_reason", JSONObject.NULL)
                        .put("usage", JSONObject().put("input_tokens", 0).put("output_tokens", 0)),
                ),
            ),
        )

        suspend fun openBlock(kind: String): Int {
            val idx = nextIdx++
            val block = if (kind == "text") JSONObject().put("type", "text").put("text", "")
            else JSONObject().put("type", "thinking").put("thinking", "")
            emit(event("content_block_start", JSONObject().put("index", idx).put("content_block", block)))
            return idx
        }

        suspend fun closeBlock(idx: Int) {
            if (idx >= 0) emit(event("content_block_stop", JSONObject().put("index", idx)))
        }

        try {
            while (true) {
                val line = channel.readUTF8Line(1 shl 20) ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                val j = try {
                    JSONObject(payload)
                } catch (e: Exception) {
                    continue
                }
                sawEvent = true

                j.optJSONObject("usage")?.let { u ->
                    inTokens = u.optLong("prompt_tokens", inTokens)
                    outTokens = u.optLong("completion_tokens", outTokens)
                }

                val choice = j.optJSONArray("choices")?.optJSONObject(0)
                val d = choice?.optJSONObject("delta") ?: JSONObject()

                val reasoning = d.optString("reasoning_content", "")
                if (reasoning.isNotEmpty()) {
                    if (thinkOpen < 0) thinkOpen = openBlock("thinking")
                    outChars += reasoning.length
                    emit(
                        event(
                            "content_block_delta",
                            JSONObject().put("index", thinkOpen)
                                .put("delta", JSONObject().put("type", "thinking_delta").put("thinking", reasoning)),
                        ),
                    )
                }

                val content = d.optString("content", "")
                if (content.isNotEmpty()) {
                    if (thinkOpen >= 0) { closeBlock(thinkOpen); thinkOpen = -1 }
                    if (textOpen < 0) textOpen = openBlock("text")
                    outChars += content.length
                    emit(
                        event(
                            "content_block_delta",
                            JSONObject().put("index", textOpen)
                                .put("delta", JSONObject().put("type", "text_delta").put("text", content)),
                        ),
                    )
                }

                val toolCalls = d.optJSONArray("tool_calls")
                if (toolCalls != null) {
                    for (i in 0 until toolCalls.length()) {
                        val tc = toolCalls.optJSONObject(i) ?: continue
                        val index = tc.optInt("index", 0)
                        if (!toolBlocks.containsKey(index)) {
                            closeBlock(textOpen); textOpen = -1
                            closeBlock(thinkOpen); thinkOpen = -1
                            val idx = nextIdx++
                            toolBlocks[index] = idx
                            val fn = tc.optJSONObject("function") ?: JSONObject()
                            emit(
                                event(
                                    "content_block_start",
                                    JSONObject().put("index", idx).put(
                                        "content_block",
                                        JSONObject()
                                            .put("type", "tool_use")
                                            .put("id", tc.optString("id", "call_${UUID.randomUUID()}"))
                                            .put("name", fn.optString("name", ""))
                                            .put("input", JSONObject()),
                                    ),
                                ),
                            )
                        }
                        val args = tc.optJSONObject("function")?.optString("arguments", "") ?: ""
                        if (args.isNotEmpty()) {
                            outChars += args.length
                            emit(
                                event(
                                    "content_block_delta",
                                    JSONObject().put("index", toolBlocks[index])
                                        .put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", args)),
                                ),
                            )
                        }
                    }
                }

                val fr = choice?.optString("finish_reason", "") ?: ""
                if (fr.isNotEmpty() && fr != "null") stopReason = STOP_MAP[fr] ?: "end_turn"
            }
        } catch (e: Exception) {
            emit(
                event(
                    "error",
                    JSONObject().put("error", JSONObject().put("type", "api_error").put("message", "upstream stream error: ${e.message}")),
                ),
            )
        }

        closeBlock(textOpen); textOpen = -1
        closeBlock(thinkOpen); thinkOpen = -1
        for (idx in toolBlocks.values) closeBlock(idx)

        if (!sawEvent) {
            emit(event("error", JSONObject().put("error", JSONObject().put("type", "api_error").put("message", "empty upstream stream"))))
        }
        if (outTokens == 0L) outTokens = outChars / 4

        emit(
            event(
                "message_delta",
                JSONObject()
                    .put("delta", JSONObject().put("stop_reason", stopReason ?: "end_turn").put("stop_sequence", JSONObject.NULL))
                    .put("usage", JSONObject().put("output_tokens", outTokens)),
            ),
        )
        emit(event("message_stop", JSONObject()))
    }

    private fun errorJson(message: String): String =
        JSONObject()
            .put("type", "error")
            .put("error", JSONObject().put("type", "api_error").put("message", message))
            .toString()
}
