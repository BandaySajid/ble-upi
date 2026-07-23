package com.bleupi.protocol

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

object Ed25519 {
    fun sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        val params = Ed25519PrivateKeyParameters(privateKey, 0)
        val signer = Ed25519Signer()
        signer.init(true, params)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        return try {
            val params = Ed25519PublicKeyParameters(publicKey, 0)
            val signer = Ed25519Signer()
            signer.init(false, params)
            signer.update(message, 0, message.size)
            signer.verifySignature(signature)
        } catch (e: Exception) {
            false
        }
    }

    fun derivePublicKey(privateKey: ByteArray): ByteArray {
        val params = Ed25519PrivateKeyParameters(privateKey, 0)
        val publicKey = params.generatePublicKey()
        return publicKey.encoded
    }
}
