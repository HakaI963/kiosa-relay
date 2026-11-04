package com.kiosa.relay.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.kiosa.relay.core.ApiKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class RelaySettings(
    val port: Int = 8849,
    val lanEnabled: Boolean = true,
    val requireAccessKey: Boolean = true,
    val accessKey: String = "",
    val cooldownSec: Int = 60,
    val errorCooldownSec: Int = 15,
    val authCooldownSec: Int = 300,
    val maxWaitSec: Int = 90,
    val requestTimeoutSec: Int = 300,
    val upstreamBaseUrl: String = "https://kiosapi.com/v1",
    val paused: Boolean = false,
)

/** Persists settings (plain prefs) and API keys (encrypted prefs). Keys never leave the device. */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("kiosa_settings", Context.MODE_PRIVATE)

    private val secure: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "kiosa_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun loadSettings(): RelaySettings {
        val d = RelaySettings()
        return RelaySettings(
            port = prefs.getInt("port", d.port).coerceIn(1024, 65535),
            lanEnabled = prefs.getBoolean("lan", d.lanEnabled),
            requireAccessKey = prefs.getBoolean("requireKey", d.requireAccessKey),
            accessKey = prefs.getString("accessKey", d.accessKey) ?: d.accessKey,
            cooldownSec = prefs.getInt("cooldownSec", d.cooldownSec).coerceIn(5, 3600),
            errorCooldownSec = prefs.getInt("errorCooldownSec", d.errorCooldownSec).coerceIn(1, 3600),
            authCooldownSec = prefs.getInt("authCooldownSec", d.authCooldownSec).coerceIn(10, 86400),
            maxWaitSec = prefs.getInt("maxWaitSec", d.maxWaitSec).coerceIn(0, 3600),
            requestTimeoutSec = prefs.getInt("requestTimeoutSec", d.requestTimeoutSec).coerceIn(30, 3600),
            upstreamBaseUrl = prefs.getString("upstream", d.upstreamBaseUrl) ?: d.upstreamBaseUrl,
            paused = prefs.getBoolean("paused", d.paused),
        )
    }

    fun saveSettings(s: RelaySettings) {
        prefs.edit()
            .putInt("port", s.port)
            .putBoolean("lan", s.lanEnabled)
            .putBoolean("requireKey", s.requireAccessKey)
            .putString("accessKey", s.accessKey)
            .putInt("cooldownSec", s.cooldownSec)
            .putInt("errorCooldownSec", s.errorCooldownSec)
            .putInt("authCooldownSec", s.authCooldownSec)
            .putInt("maxWaitSec", s.maxWaitSec)
            .putInt("requestTimeoutSec", s.requestTimeoutSec)
            .putString("upstream", s.upstreamBaseUrl)
            .putBoolean("paused", s.paused)
            .apply()
    }

    fun loadKeys(): List<ApiKey> {
        val raw = secure.getString("keys", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ApiKey(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    label = o.optString("label", "Key"),
                    secret = o.optString("secret", ""),
                    enabled = o.optBoolean("enabled", true),
                )
            }.filter { it.secret.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveKeys(keys: List<ApiKey>) {
        val arr = JSONArray()
        keys.forEach { k ->
            arr.put(
                JSONObject()
                    .put("id", k.id)
                    .put("label", k.label)
                    .put("secret", k.secret)
                    .put("enabled", k.enabled),
            )
        }
        secure.edit().putString("keys", arr.toString()).apply()
    }

    companion object {
        fun newAccessKey(): String = "kr_" + UUID.randomUUID().toString().replace("-", "").take(26)
    }
}
