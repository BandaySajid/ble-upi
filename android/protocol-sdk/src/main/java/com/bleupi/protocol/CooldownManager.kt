package com.bleupi.protocol

import java.util.concurrent.ConcurrentHashMap

class CooldownManager(private val windowDurationSeconds: Long = 180) {

    private data class NonceEntry(val merchantShortHash: Int, val nonce: Long, val timestamp: Long)

    private val seenNonces = ConcurrentHashMap<String, Long>()

    fun shouldAllow(merchantShortHash: Int, nonce: Long): Boolean {
        val key = "$merchantShortHash:$nonce"
        val now = System.currentTimeMillis()
        val lastSeen = seenNonces[key]

        return if (lastSeen != null && (now - lastSeen) < windowDurationSeconds * 1000) {
            false
        } else {
            seenNonces[key] = now
            true
        }
    }

    fun reset(merchantShortHash: Int) {
        val prefix = "$merchantShortHash:"
        seenNonces.keys.removeIf { it.startsWith(prefix) }
    }

    fun clear() {
        seenNonces.clear()
    }

    val activeEntries: Int get() = seenNonces.size
}
