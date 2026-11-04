package com.kiosa.relay.core

data class ApiKey(
    val id: String,
    val label: String,
    val secret: String,
    val enabled: Boolean = true,
)

data class LogEntry(
    val t: Long,
    val kind: String, // ok | cooldown | error | wait | reject
    val key: String,
    val msg: String,
)

data class Stats(
    val relayed: Long = 0,
    val rateLimited: Long = 0,
    val errors: Long = 0,
    val waits: Long = 0,
)

data class KeyView(
    val id: String,
    val label: String,
    val masked: String,
    val enabled: Boolean,
    val status: KeyStatus,
    val cooldownUntil: Long,
    val success: Long,
    val rateLimited: Long,
    val fail: Long,
    val lastError: String?,
)

data class EngineSnapshot(
    val stats: Stats = Stats(),
    val keys: List<KeyView> = emptyList(),
    val log: List<LogEntry> = emptyList(),
)
