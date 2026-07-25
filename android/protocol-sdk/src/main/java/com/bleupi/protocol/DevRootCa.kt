package com.bleupi.protocol

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters

object DevRootCa {
    val publicKey: ByteArray
    val privateKey: ByteArray

    init {
        val seedMaterial = "BLE-UPI-DEV-ROOT-CA-v1".toByteArray(Charsets.US_ASCII)
        val digest = SHA256Digest()
        val seed = ByteArray(32)
        digest.update(seedMaterial, 0, seedMaterial.size)
        digest.doFinal(seed, 0)
        val params = Ed25519PrivateKeyParameters(seed, 0)
        privateKey = seed
        publicKey = params.generatePublicKey().encoded
    }
}
