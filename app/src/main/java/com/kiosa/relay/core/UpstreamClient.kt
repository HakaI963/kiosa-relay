package com.kiosa.relay.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

/** Raw streaming HTTP client for the upstream KiosAPI endpoint. */
class UpstreamClient {

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 300_000
            requestTimeoutMillis = 0 // never kill a long stream; socket timeout handles stalls
        }
    }

    suspend fun <T> post(
        url: String,
        body: ByteArray,
        secret: String,
        socketTimeoutMs: Long,
        block: suspend (HttpResponse) -> T,
    ): T = client.preparePost(url) {
        header(HttpHeaders.Authorization, "Bearer $secret")
        contentType(ContentType.Application.Json)
        setBody(body)
        timeout {
            requestTimeoutMillis = 0
            socketTimeoutMillis = socketTimeoutMs
        }
    }.execute(block)

    suspend fun getText(url: String, secret: String, timeoutMs: Long): HttpResponse =
        client.get(url) {
            header(HttpHeaders.Authorization, "Bearer $secret")
            timeout {
                requestTimeoutMillis = timeoutMs
                socketTimeoutMillis = timeoutMs
            }
        }

    fun close() {
        client.close()
    }
}
