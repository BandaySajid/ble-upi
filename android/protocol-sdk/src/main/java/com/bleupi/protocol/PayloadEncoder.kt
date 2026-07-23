package com.bleupi.protocol

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

object PayloadEncoder {
    private const val PROTOCOL_VERSION_V1 = 0x04
    private const val DOMAIN_SEPARATOR = "OBP1"
    private const val CERT_DOMAIN_SEPARATOR = "OBP1-CERT"

    fun encode(
        vpa: String,
        displayName: String,
        amountPaise: Long,
        merchantPublicKey: ByteArray,
        merchantPrivateKey: ByteArray,
        txPower: Byte,
        caPrivateKey: ByteArray
    ): ByteArray {
        val dictVersion = CompressionDictionary.version
        val vpaParts = vpa.split("@")
        val handle = if (vpaParts.size == 2) vpaParts[1] else vpa
        val suffixIndex = CompressionDictionary.getIndex(handle)

        val merchantShortHash = sha256First4(merchantPublicKey)
        val nonce = currentNonce()

        val header = buildHeader(
            mode = PayloadMode.SINGLE_FRAME,
            dictVersion = dictVersion,
            shortHash = merchantShortHash,
            suffixIndex = suffixIndex,
            amountPaise = amountPaise,
            nonce = nonce
        )

        val signingInput = ByteArray(4 + 16)
        System.arraycopy(DOMAIN_SEPARATOR.toByteArray(Charsets.US_ASCII), 0, signingInput, 0, 4)
        System.arraycopy(header, 0, signingInput, 4, 16)
        val signature = Ed25519.sign(merchantPrivateKey, signingInput)

        val certBytes = encodeCertificate(vpa, displayName, merchantPublicKey, caPrivateKey)

        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(signature)
        out.write(txPower.toInt())
        if (suffixIndex == 0) {
            out.write(handle.length)
            out.write(handle.toByteArray(Charsets.US_ASCII))
        }
        out.write(certBytes)
        return out.toByteArray()
    }

    fun encodeMultiFrame(
        vpa: String,
        displayName: String,
        amountPaise: Long,
        merchantPublicKey: ByteArray,
        merchantPrivateKey: ByteArray,
        txPower: Byte,
        caPrivateKey: ByteArray,
        maxChunkPayloadSize: Int = 239
    ): List<ByteArray> {
        val fullPayload = encode(vpa, displayName, amountPaise, merchantPublicKey, merchantPrivateKey, txPower, caPrivateKey)
        val totalChunks = ((fullPayload.size + maxChunkPayloadSize - 1) / maxChunkPayloadSize).coerceAtMost(15)
        val chunks = mutableListOf<ByteArray>()

        for (i in 0 until totalChunks) {
            val start = i * maxChunkPayloadSize
            val end = minOf(start + maxChunkPayloadSize, fullPayload.size)
            val chunkData = fullPayload.copyOfRange(start, end)
            val chunk = ByteArray(chunkData.size + 1)
            chunk[0] = ((i shl 4) or totalChunks).toByte()
            System.arraycopy(chunkData, 0, chunk, 1, chunkData.size)
            chunks.add(chunk)
        }
        return chunks
    }

    internal fun buildHeader(
        mode: PayloadMode,
        dictVersion: Int,
        shortHash: Int,
        suffixIndex: Int,
        amountPaise: Long,
        nonce: Long
    ): ByteArray {
        val buf = ByteArray(16)
        buf[0] = (PROTOCOL_VERSION_V1 or mode.flag).toByte()
        buf[1] = dictVersion.toByte()
        buf[2] = ((shortHash shr 24) and 0xFF).toByte()
        buf[3] = ((shortHash shr 16) and 0xFF).toByte()
        buf[4] = ((shortHash shr 8) and 0xFF).toByte()
        buf[5] = (shortHash and 0xFF).toByte()
        buf[6] = suffixIndex.toByte()
        buf[7] = ((amountPaise shr 16) and 0xFF).toByte()
        buf[8] = ((amountPaise shr 8) and 0xFF).toByte()
        buf[9] = (amountPaise and 0xFF).toByte()
        buf[10] = 0
        buf[11] = 0
        buf[12] = ((nonce shr 24) and 0xFF).toByte()
        buf[13] = ((nonce shr 16) and 0xFF).toByte()
        buf[14] = ((nonce shr 8) and 0xFF).toByte()
        buf[15] = (nonce and 0xFF).toByte()
        return buf
    }

    internal fun encodeCertificate(
        vpa: String,
        displayName: String,
        publicKey: ByteArray,
        caPrivateKey: ByteArray
    ): ByteArray {
        val certWithoutSig = encodeCborMap(
            mapOf(
                1 to CborValue.Bytes(publicKey),
                2 to CborValue.Text(vpa),
                3 to CborValue.Text(displayName)
            )
        )
        val certSigningInput = ByteArray(8 + certWithoutSig.size)
        System.arraycopy(CERT_DOMAIN_SEPARATOR.toByteArray(Charsets.US_ASCII), 0, certSigningInput, 0, 8)
        System.arraycopy(certWithoutSig, 0, certSigningInput, 8, certWithoutSig.size)
        val caSignature = Ed25519.sign(caPrivateKey, certSigningInput)

        return encodeCborMap(
            mapOf(
                1 to CborValue.Bytes(publicKey),
                2 to CborValue.Text(vpa),
                3 to CborValue.Text(displayName),
                4 to CborValue.Bytes(caSignature)
            )
        )
    }

    private fun sha256First4(data: ByteArray): Int {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return ((hash[0].toInt() and 0xFF) shl 24) or
            ((hash[1].toInt() and 0xFF) shl 16) or
            ((hash[2].toInt() and 0xFF) shl 8) or
            (hash[3].toInt() and 0xFF)
    }

    fun currentNonce(): Long = System.currentTimeMillis() / 1000 / 30

    internal fun generateKeypair(): Pair<ByteArray, ByteArray> {
        val privateKey = ByteArray(32)
        SecureRandom().nextBytes(privateKey)
        val publicKey = Ed25519.derivePublicKey(privateKey)
        return Pair(publicKey, privateKey)
    }
}
