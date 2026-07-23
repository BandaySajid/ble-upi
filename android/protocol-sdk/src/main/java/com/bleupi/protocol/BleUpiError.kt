package com.bleupi.protocol

enum class BleUpiError(val code: Int) {
    INVALID_PROTOCOL_VERSION(1),
    INVALID_PAYLOAD_MODE(2),
    INVALID_DICTIONARY_VERSION(3),
    EXPIRED_NONCE(4),
    SIGNATURE_INVALID(5),
    CERT_INVALID(6),
    CERT_MISSING_PROFILE(7),
    TRUNCATED_PAYLOAD(8),
    CHUNK_TIMEOUT(9),
    CHUNK_OVERFLOW(10);

    companion object {
        fun fromCode(code: Int): BleUpiError? = entries.find { it.code == code }
    }
}
