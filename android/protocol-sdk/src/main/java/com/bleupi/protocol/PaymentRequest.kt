package com.bleupi.protocol

data class PaymentRequest(
    val header: PayloadHeader,
    val vpa: String,
    val displayName: String,
    val merchantPublicKey: ByteArray,
    val signature: ByteArray,
    val txPower: Byte,
    val caSignature: ByteArray
) {
    val merchantId: String get() = header.merchantShortHash.toString(16)

    val upiUri: String
        get() {
            val am = if (header.amountPaise > 0) "&am=%.2f".format(header.amountPaise / 100.0) else ""
            return "upi://pay?pa=$vpa&pn=$displayName$am&tr=${header.nonce}&tn=BLE-UPI"
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PaymentRequest) return false
        return header == other.header &&
            vpa == other.vpa &&
            displayName == other.displayName &&
            merchantPublicKey.contentEquals(other.merchantPublicKey) &&
            signature.contentEquals(other.signature) &&
            txPower == other.txPower &&
            caSignature.contentEquals(other.caSignature)
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + vpa.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + merchantPublicKey.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + txPower.toInt()
        result = 31 * result + caSignature.contentHashCode()
        return result
    }
}
