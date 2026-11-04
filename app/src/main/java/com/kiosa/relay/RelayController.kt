package com.kiosa.relay

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import com.kiosa.relay.core.ApiKey
import com.kiosa.relay.core.RelayEngine
import com.kiosa.relay.server.RelayServer
import com.kiosa.relay.service.RelayService
import com.kiosa.relay.store.RelaySettings
import com.kiosa.relay.store.SettingsStore
import java.net.NetworkInterface
import java.util.UUID

enum class ServerState { STOPPED, STARTING, RUNNING, ERROR }

object RelayController {

    lateinit var store: SettingsStore
        private set

    val engine = RelayEngine()

    private val _settings = MutableStateFlow(RelaySettings())
    val settings: StateFlow<RelaySettings> = _settings

    private val _keys = MutableStateFlow<List<ApiKey>>(emptyList())
    val keys: StateFlow<List<ApiKey>> = _keys

    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState: StateFlow<ServerState> = _serverState

    private var server: RelayServer? = null

    fun init(context: Context) {
        if (::store.isInitialized) return
        store = SettingsStore(context.applicationContext)
        val s = store.loadSettings().let {
            if (it.accessKey.isBlank()) it.copy(accessKey = SettingsStore.newAccessKey()) else it
        }
        store.saveSettings(s)
        _settings.value = s
        setKeysInternal(store.loadKeys())
    }

    // --------------------------- server lifecycle ---------------------------

    fun startRelay(context: Context) {
        if (_serverState.value == ServerState.RUNNING || _serverState.value == ServerState.STARTING) return
        _serverState.value = ServerState.STARTING
        val intent = Intent(context, RelayService::class.java)
        context.startForegroundService(intent)
    }

    /** Called by RelayService after reaching the foreground. */
    fun actuallyStart() {
        try {
            val s = _settings.value
            RelayServer(engine) { _settings.value }.let {
                it.start(if (s.lanEnabled) "0.0.0.0" else "127.0.0.1", s.port)
                server = it
            }
            _serverState.value = ServerState.RUNNING
            engine.markRuntime("server started on :${_settings.value.port}${if (_settings.value.lanEnabled) " (LAN)" else ""}")
        } catch (e: Exception) {
            _serverState.value = ServerState.ERROR
            engine.markRuntime("server failed: ${e.message}")
        }
    }

    fun stopRelay(context: Context) {
        server?.stop()
        server = null
        _serverState.value = ServerState.STOPPED
        engine.markRuntime("server stopped")
        context.stopService(Intent(context, RelayService::class.java))
    }

    // ------------------------------ settings -------------------------------

    fun updateSettings(transform: (RelaySettings) -> RelaySettings) {
        val next = transform(_settings.value)
        store.saveSettings(next)
        _settings.value = next
    }

    fun regenerateAccessKey() = updateSettings { it.copy(accessKey = SettingsStore.newAccessKey()) }

    // -------------------------------- keys ---------------------------------

    fun addKey(label: String, secret: String) {
        val k = ApiKey(id = UUID.randomUUID().toString(), label = label.ifBlank { "Key ${_keys.value.size + 1}" }, secret = secret.trim())
        setKeysInternal(_keys.value + k)
    }

    fun updateKey(updated: ApiKey) {
        setKeysInternal(_keys.value.map { if (it.id == updated.id) updated else it })
    }

    fun deleteKey(id: String) = setKeysInternal(_keys.value.filterNot { it.id == id })

    fun moveKey(id: String, up: Boolean) {
        val list = _keys.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val target = if (up) idx - 1 else idx + 1
        if (target !in list.indices) return
        list[idx] = list[target].also { list[target] = list[idx] }
        setKeysInternal(list)
    }

    private fun setKeysInternal(list: List<ApiKey>) {
        store.saveKeys(list)
        _keys.value = list
        engine.setKeys(list)
    }

    /** Connectivity test for a single key — hits GET /models (does not burn chat quota). */
    suspend fun testKey(key: ApiKey): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val client = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 12_000
                connectTimeoutMillis = 10_000
            }
        }
        try {
            val resp = client.get(_settings.value.upstreamBaseUrl.trimEnd('/') + "/models") {
                header("Authorization", "Bearer ${key.secret}")
            }
            val ok = resp.status.value == 200
            ok to if (ok) "Key valid — /models returned 200" else "HTTP ${resp.status.value}: ${resp.bodyAsText().take(140)}"
        } catch (e: Exception) {
            false to (e.message ?: "network error")
        } finally {
            client.close()
        }
    }

    // -------------------------------- misc ---------------------------------

    fun lanIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
