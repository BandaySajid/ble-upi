package com.bleupi.protocol

object CryptoVerifier {
    private const val DOMAIN_SEPARATOR = "OBP1"
    private const val CERT_DOMAIN_SEPARATOR = "OBP1-CERT"

    fun verifyPayload(request: PaymentRequest): Boolean {
        return try {
            val signingInput = buildSigningInput(request)
            Ed25519.verify(request.merchantPublicKey, signingInput, request.signature)
        } catch (e: Exception) {
            false
        }
    }

    fun verifyNonce(nonce: Long, currentWindow: Long = PayloadEncoder.currentNonce()): Boolean {
        val diff = currentWindow - nonce
        return diff in -1..6
    }

    fun verifyCertificate(
        publicKey: ByteArray,
        vpa: String,
        displayName: String,
        caSignature: ByteArray,
        rootCaPublicKey: ByteArray
    ): Boolean {
        return try {
            val certBytes = encodeCborMap(
                mapOf(
                    1 to CborValue.Bytes(publicKey),
                    2 to CborValue.Text(vpa),
                    3 to CborValue.Text(displayName)
                )
            )
            val certSigningInput = ByteArray(8 + certBytes.size)
            System.arraycopy(CERT_DOMAIN_SEPARATOR.toByteArray(Charsets.US_ASCII), 0, certSigningInput, 0, 8)
            System.arraycopy(certBytes, 0, certSigningInput, 8, certBytes.size)
            Ed25519.verify(rootCaPublicKey, certSigningInput, caSignature)
        } catch (e: Exception) {
            false
        }
    }

    internal fun buildSigningInput(request: PaymentRequest): ByteArray {
        val header = PayloadEncoder.buildHeader(
            mode = request.header.mode,
            dictVersion = request.header.dictionaryVersion,
            shortHash = request.header.merchantShortHash,
            suffixIndex = request.header.vpaSuffixIndex,
            amountPaise = request.header.amountPaise,
            nonce = request.header.nonce
        )
        val input = ByteArray(4 + 16)
        System.arraycopy(DOMAIN_SEPARATOR.toByteArray(Charsets.US_ASCII), 0, input, 0, 4)
        System.arraycopy(header, 0, input, 4, 16)
        return input
    }
}
