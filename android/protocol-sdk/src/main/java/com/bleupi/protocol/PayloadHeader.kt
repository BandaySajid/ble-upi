package com.bleupi.protocol

data class PayloadHeader(
    val protocolVersion: Int,
    val mode: PayloadMode,
    val dictionaryVersion: Int,
    val merchantShortHash: Int,
    val vpaSuffixIndex: Int,
    val amountPaise: Long,
    val nonce: Long
)
