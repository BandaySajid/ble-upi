package com.bleupi.protocol

data class MerchantProfile(
    val shortHash: Int,
    val displayName: String,
    val vpa: String,
    val publicKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MerchantProfile) return false
        return shortHash == other.shortHash &&
            displayName == other.displayName &&
            vpa == other.vpa &&
            publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int {
        var result = shortHash
        result = 31 * result + displayName.hashCode()
        result = 31 * result + vpa.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
}
