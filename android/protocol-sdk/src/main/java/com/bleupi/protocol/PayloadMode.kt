package com.bleupi.protocol

enum class PayloadMode(val flag: Int) {
    SINGLE_FRAME(0x00),
    MULTI_FRAME_CHUNKED(0x01),
    GATT_ESCALATION(0x02);

    companion object {
        fun fromFlag(flag: Int): PayloadMode? = entries.find { it.flag == flag }
    }
}
