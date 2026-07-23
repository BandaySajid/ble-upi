package com.bleupi.protocol

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PayloadCodecTest {

    private val merchantKeypair = PayloadEncoder.generateKeypair()
    private val caPrivateKey = DevRootCa.privateKey
    private val caPublicKey = DevRootCa.publicKey

    @Test
    fun `round-trip encode decode with compressed VPA`() {
        val vpa = "shop@okaxis"
        val displayName = "Ram General Store"
        val amountPaise = 15000L

        val payload = PayloadEncoder.encode(
            vpa = vpa,
            displayName = displayName,
            amountPaise = amountPaise,
            merchantPublicKey = merchantKeypair.first,
            merchantPrivateKey = merchantKeypair.second,
            txPower = -26,
            caPrivateKey = caPrivateKey
        )

        val result = PayloadDecoder.decode(payload)
        assertTrue(result is DecodeResult.Success)
        val request = (result as DecodeResult.Success).request

        assertEquals(1, request.header.protocolVersion)
        assertEquals(PayloadMode.SINGLE_FRAME, request.header.mode)
        assertEquals(CompressionDictionary.version, request.header.dictionaryVersion)
        assertEquals(15000L, request.header.amountPaise)
        assertEquals(0x01, request.header.vpaSuffixIndex)
        assertEquals(-26.toByte(), request.txPower)
        assertEquals(vpa, request.vpa)
        assertEquals(displayName, request.displayName)
        assertArrayEquals(merchantKeypair.first, request.merchantPublicKey)
    }

    @Test
    fun `round-trip with raw VPA suffix when handle not in dictionary`() {
        val vpa = "kirana@rarebank"
        val displayName = "Rare Bank Store"
        val amountPaise = 2550L

        val payload = PayloadEncoder.encode(
            vpa = vpa,
            displayName = displayName,
            amountPaise = amountPaise,
            merchantPublicKey = merchantKeypair.first,
            merchantPrivateKey = merchantKeypair.second,
            txPower = 0,
            caPrivateKey = caPrivateKey
        )

        val result = PayloadDecoder.decode(payload)
        assertTrue(result is DecodeResult.Success)
        val request = (result as DecodeResult.Success).request

        assertEquals(0, request.header.vpaSuffixIndex)
        assertEquals(vpa, request.vpa)
        assertEquals(displayName, request.displayName)
        assertEquals(2550L, request.header.amountPaise)
    }

    @Test
    fun `amount zero means no amount set`() {
        val payload = PayloadEncoder.encode(
            vpa = "shop@okhdfcbank",
            displayName = "Test",
            amountPaise = 0,
            merchantPublicKey = merchantKeypair.first,
            merchantPrivateKey = merchantKeypair.second,
            txPower = 0,
            caPrivateKey = caPrivateKey
        )

        val result = PayloadDecoder.decode(payload)
        assertTrue(result is DecodeResult.Success)
        assertEquals(0L, (result as DecodeResult.Success).request.header.amountPaise)
    }

    @Test
    fun `max amount value`() {
        val maxPaise = 0xFFFFFFL
        val payload = PayloadEncoder.encode(
            vpa = "shop@oksbi",
            displayName = "MaxAmount",
            amountPaise = maxPaise,
            merchantPublicKey = merchantKeypair.first,
            merchantPrivateKey = merchantKeypair.second,
            txPower = 0,
            caPrivateKey = caPrivateKey
        )

        val result = PayloadDecoder.decode(payload)
        assertTrue(result is DecodeResult.Success)
        assertEquals(maxPaise, (result as DecodeResult.Success).request.header.amountPaise)
    }

    @Test
    fun `valid signature verifies`() {
        val payload = PayloadEncoder.encode(
            vpa = "shop@okaxis",
            displayName = "Test Merchant",
            amountPaise = 1000L,
            merchantPublicKey = merchantKeypair.first,
            merchantPrivateKey = merchantKeypair.second,
            txPower = 0,
            caPrivateKey = caPrivateKey
        )
        val result = PayloadDecoder.decode(payload)
        val request = (result as DecodeResult.Success).request

        assertTrue(CryptoVerifier.verifyPayload(request))
    }

    @Test
    fun `tampered payload fails verification`() {
        val payload = PayloadEncoder.encode(
            vpa = "shop@okaxis",
            displayName = "Test Merchant",
            amountPaise = 1000L,
            merchantPublicKey = merchantKeypair.first,
            merchantPrivateKey = merchantKeypair.second,
            txPower = 0,
            caPrivateKey = caPrivateKey
        )
        val tampered = payload.clone()
        tampered[20] = (tampered[20].toInt() xor 0x01).toByte()

        val result = PayloadDecoder.decode(tampered)
        val request = (result as DecodeResult.Success).request
        assertFalse(CryptoVerifier.verifyPayload(request))
    }

    @Test
    fun `expired nonce rejected`() {
        val oldNonce = PayloadEncoder.currentNonce() - 10
        assertFalse(CryptoVerifier.verifyNonce(oldNonce))
    }

    @Test
    fun `valid nonce within window accepted`() {
        val currentNonce = PayloadEncoder.currentNonce()
        assertTrue(CryptoVerifier.verifyNonce(currentNonce))
        assertTrue(CryptoVerifier.verifyNonce(currentNonce - 1))
        assertTrue(CryptoVerifier.verifyNonce(currentNonce - 6))
    }

    @Test
    fun `future nonce more than 1 window rejected`() {
        val futureNonce = PayloadEncoder.currentNonce() + 2
        assertFalse(CryptoVerifier.verifyNonce(futureNonce))
    }

    @Test
    fun `wrong domain separator rejects`() {
        val vpa = "shop@okaxis"
        val displayName = "Test"
        val amountPaise = 1000L

        val header = PayloadEncoder.buildHeader(
            mode = PayloadMode.SINGLE_FRAME,
            dictVersion = CompressionDictionary.version,
            shortHash = 0x12345678,
            suffixIndex = 0x01,
            amountPaise = amountPaise,
            nonce = PayloadEncoder.currentNonce()
        )

        val wrongInput = ByteArray(4 + 16)
        System.arraycopy("OBP0".toByteArray(Charsets.US_ASCII), 0, wrongInput, 0, 4)
        System.arraycopy(header, 0, wrongInput, 4, 16)

        val signature = Ed25519.sign(merchantKeypair.second, wrongInput)

        val correctInput = ByteArray(4 + 16)
        System.arraycopy("OBP1".toByteArray(Charsets.US_ASCII), 0, correctInput, 0, 4)
        System.arraycopy(header, 0, correctInput, 4, 16)

        assertFalse(Ed25519.verify(merchantKeypair.first, correctInput, signature))
    }

    @Test
    fun `certificate verification passes with correct CA`() {
        val vpa = "shop@okaxis"
        val displayName = "CertTest"
        assertTrue(
            CryptoVerifier.verifyCertificate(
                publicKey = merchantKeypair.first,
                vpa = vpa,
                displayName = displayName,
                caSignature = signCert(vpa, displayName, merchantKeypair.first, caPrivateKey),
                rootCaPublicKey = caPublicKey
            )
        )
    }

    @Test
    fun `tampered certificate fails CA verification`() {
        val vpa = "shop@okaxis"
        val displayName = "TamperedCert"
        val certSig = signCert(vpa, displayName, merchantKeypair.first, caPrivateKey)
        val tamperedSig = certSig.clone()
        tamperedSig[0] = (tamperedSig[0].toInt() xor 0x01).toByte()

        assertFalse(
            CryptoVerifier.verifyCertificate(
                publicKey = merchantKeypair.first,
                vpa = vpa,
                displayName = displayName,
                caSignature = tamperedSig,
                rootCaPublicKey = caPublicKey
            )
        )
    }

    private fun signCert(vpa: String, displayName: String, pk: ByteArray, caKey: ByteArray): ByteArray {
        val certBytes = encodeCborMap(
            mapOf(
                1 to CborValue.Bytes(pk),
                2 to CborValue.Text(vpa),
                3 to CborValue.Text(displayName)
            )
        )
        val input = ByteArray(8 + certBytes.size)
        System.arraycopy("OBP1-CERT".toByteArray(Charsets.US_ASCII), 0, input, 0, 8)
        System.arraycopy(certBytes, 0, input, 8, certBytes.size)
        return Ed25519.sign(caKey, input)
    }
}
