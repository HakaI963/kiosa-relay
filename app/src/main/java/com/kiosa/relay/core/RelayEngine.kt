package com.kiosa.relay.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

enum class KeyStatus { IDLE, ACTIVE, COOLDOWN, ERROR, DISABLED }

/**
 * Heart of Kiosa Relay — the tested strategy (validated against the real API):
 *
 *   - STRICT SEQUENTIAL ORDER, not round-robin: always the first key in list order
 *     that is enabled and not cooling down. Key 1 is used until it rate-limits,
 *     then key 2, and so on; when key 1 revives it is preferred again.
 *   - A 429 puts that key on cooldown (default 60 s). Errors get a short cooldown,
 *     auth errors a long one.
 *   - When EVERY key is cooling, requests wait for the soonest revival
 *     (bounded by max-wait) instead of failing the coding agent.
 */
class RelayEngine {

    class Slot(val apiKey: ApiKey) {
        @Volatile var cooldownUntil: Long = 0L
        @Volatile var lastError: String? = null
        @Volatile var lastUsedAt: Long = 0L
        val success = AtomicLong(0)
        val rateLimited = AtomicLong(0)
        val fail = AtomicLong(0)
    }

    private val slots = CopyOnWriteArrayList<Slot>()
    private val logBuf = ArrayDeque<LogEntry>()
    private val logLock = Any()

    private val cRelayed = AtomicLong(0)
    private val cRateLimited = AtomicLong(0)
    private val cErrors = AtomicLong(0)
    private val cWaits = AtomicLong(0)

    private val _snapshot = MutableStateFlow(EngineSnapshot())
    val snapshot: StateFlow<EngineSnapshot> = _snapshot

    fun setKeys(keys: List<ApiKey>) {
        synchronized(logLock) {
            val old = slots.associateBy { it.apiKey.id }
            val fresh = keys.map { k ->
                val preserved = old[k.id]
                Slot(k).also { s ->
                    if (preserved != null) {
                        s.cooldownUntil = preserved.cooldownUntil
                        s.lastError = preserved.lastError
                        s.lastUsedAt = preserved.lastUsedAt
                        s.success.set(preserved.success.get())
                        s.rateLimited.set(preserved.rateLimited.get())
                        s.fail.set(preserved.fail.get())
                    }
                }
            }
            slots.clear()
            slots.addAll(fresh)
            publishLocked()
        }
    }

    fun keySecrets(): List<Pair<String, String>> = slots.map { it.apiKey.id to it.apiKey.secret }

    fun pickAvailable(): Slot? {
        val now = now()
        return slots.firstOrNull { it.apiKey.enabled && it.cooldownUntil <= now }
    }

    fun soonestRevival(): Long? =
        slots.filter { it.apiKey.enabled }.map { it.cooldownUntil }.filter { it > now() }.minOrNull()

    fun firstEnabledSecret(): String? = slots.firstOrNull { it.apiKey.enabled }?.apiKey?.secret

    fun markRateLimited(slot: Slot, detail: String, cooldownSec: Long) {
        slot.cooldownUntil = now() + cooldownSec * 1000L
        slot.rateLimited.incrementAndGet()
        slot.lastError = "429 rate limited"
        cRateLimited.incrementAndGet()
        addLog("cooldown", slot.apiKey.label, "429 -> cooldown ${cooldownSec}s ${detail.take(90)}")
    }

    fun markError(slot: Slot, msg: String, cooldownSec: Long) {
        slot.cooldownUntil = now() + cooldownSec * 1000L
        slot.fail.incrementAndGet()
        slot.lastError = msg.take(180)
        cErrors.incrementAndGet()
        addLog("error", slot.apiKey.label, "$msg -> cooldown ${cooldownSec}s")
    }

    fun markSuccess(slot: Slot, msg: String) {
        slot.success.incrementAndGet()
        slot.lastUsedAt = now()
        slot.lastError = null
        cRelayed.incrementAndGet()
        addLog("ok", slot.apiKey.label, msg)
    }

    fun markWait(waitMs: Long) {
        cWaits.incrementAndGet()
        addLog("wait", "(pool)", "all keys cooling -> waiting ${waitMs / 1000}s for revival")
    }

    fun markReject(retryAfterSec: Long) {
        addLog("reject", "(pool)", "all keys cooling -> 429 to client, retry-after ${retryAfterSec}s")
    }

    fun markRuntime(msg: String) {
        addLog("runtime", "(relay)", msg)
    }

    fun clearLog() {
        synchronized(logLock) {
            logBuf.clear()
            publishLocked()
        }
    }

    private fun addLog(kind: String, key: String, msg: String) {
        synchronized(logLock) {
            logBuf.addFirst(LogEntry(now(), kind, key, msg))
            while (logBuf.size > 200) logBuf.removeLast()
            publishLocked()
        }
    }

    private fun publishLocked() {
        val t = now()
        val views = slots.map { s ->
            val status = when {
                !s.apiKey.enabled -> KeyStatus.DISABLED
                s.cooldownUntil > t -> KeyStatus.COOLDOWN
                s.lastError != null -> KeyStatus.ERROR
                s.lastUsedAt > 0 -> KeyStatus.ACTIVE
                else -> KeyStatus.IDLE
            }
            KeyView(
                id = s.apiKey.id,
                label = s.apiKey.label,
                masked = mask(s.apiKey.secret),
                enabled = s.apiKey.enabled,
                status = status,
                cooldownUntil = s.cooldownUntil,
                success = s.success.get(),
                rateLimited = s.rateLimited.get(),
                fail = s.fail.get(),
                lastError = s.lastError,
            )
        }
        _snapshot.value = EngineSnapshot(
            stats = Stats(cRelayed.get(), cRateLimited.get(), cErrors.get(), cWaits.get()),
            keys = views,
            log = logBuf.toList(),
        )
    }

    private fun now() = System.currentTimeMillis()

    private fun mask(secret: String): String =
        if (secret.length > 12) secret.take(7) + "…" + secret.takeLast(4) else "•••"

    companion object {
        const val MAX_ATTEMPTS = 15
    }
}
