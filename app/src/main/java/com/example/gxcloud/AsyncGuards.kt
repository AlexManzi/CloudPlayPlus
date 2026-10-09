package com.example.gxcloud

internal class ExpiringValue<T>(
    private val intervalMillis: Long,
    private val now: () -> Long,
    private val load: () -> T
) {
    private data class Sample<T>(val timestamp: Long, val value: T)
    private var sample: Sample<T>? = null

    fun get(): T {
        val timestamp = now()
        val previous = sample
        if (previous != null && timestamp - previous.timestamp < intervalMillis) return previous.value
        return load().also { sample = Sample(timestamp, it) }
    }
}

// Main-thread state only; worker results return to the main thread before completion.
internal class SaveRevisionTracker {
    private var revision = 0L
    private var savedRevision = 0L
    private var pendingRevision: Long? = null

    fun changed() { revision++ }

    fun begin(): Long? {
        if (revision == savedRevision || pendingRevision == revision) return null
        pendingRevision = revision
        return revision
    }

    fun complete(version: Long, success: Boolean) {
        if (pendingRevision == version) pendingRevision = null
        if (success && version > savedRevision) savedRevision = version
    }
}

internal class RequestGate {
    private var pending: Any? = null

    fun begin(): Any? {
        if (pending != null) return null
        return Any().also { pending = it }
    }

    fun complete(request: Any): Boolean {
        if (pending !== request) return false
        pending = null
        return true
    }

    fun invalidate() { pending = null }
}

internal class DocumentInjectionGate {
    private var pending: Long? = null
    private var injected: Long? = null

    fun begin(generation: Long): Boolean {
        if (pending == generation || injected == generation) return false
        pending = generation
        return true
    }

    fun complete(generation: Long, success: Boolean) {
        if (pending != generation) return
        pending = null
        if (success) injected = generation
    }

    fun invalidate() {
        pending = null
        injected = null
    }
}
