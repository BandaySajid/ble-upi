package com.bleupi.protocol

import java.net.URLEncoder

object UpiIntentBuilder {
    fun build(request: PaymentRequest): String {
        return request.upiUri
    }

    fun buildUriParts(request: PaymentRequest): Map<String, String> {
        val parts = mutableMapOf(
            "pa" to request.vpa,
            "pn" to request.displayName,
            "tr" to request.header.nonce.toString(),
            "tn" to "BLE-UPI"
        )
        if (request.header.amountPaise > 0) {
            parts["am"] = "%.2f".format(request.header.amountPaise / 100.0)
        }
        return parts
    }
}
