package com.bleupi.protocol

object PayloadDecoder {

    fun decode(raw: ByteArray): DecodeResult {
        if (raw.size < 16) return DecodeResult.Error(BleUpiError.TRUNCATED_PAYLOAD)

        val versionByte = raw[0].toInt() and 0xFF
        val protocolVersion = (versionByte shr 2) and 0x3F
        if (protocolVersion != 1) return DecodeResult.Error(BleUpiError.INVALID_PROTOCOL_VERSION)

        val modeFlag = versionByte and 0x03
        val mode = PayloadMode.fromFlag(modeFlag) ?: return DecodeResult.Error(BleUpiError.INVALID_PAYLOAD_MODE)

        val dictVersion = raw[1].toInt() and 0xFF
        if (dictVersion != CompressionDictionary.version) return DecodeResult.Error(BleUpiError.INVALID_DICTIONARY_VERSION)

        val shortHash = ((raw[2].toInt() and 0xFF) shl 24) or
            ((raw[3].toInt() and 0xFF) shl 16) or
            ((raw[4].toInt() and 0xFF) shl 8) or
            (raw[5].toInt() and 0xFF)

        val suffixIndex = raw[6].toInt() and 0xFF

        val amountPaise = ((raw[7].toInt() and 0xFF).toLong() shl 16) or
            ((raw[8].toInt() and 0xFF).toLong() shl 8) or
            (raw[9].toInt() and 0xFF).toLong()

        val nonce = ((raw[12].toInt() and 0xFF).toLong() shl 24) or
            ((raw[13].toInt() and 0xFF).toLong() shl 16) or
            ((raw[14].toInt() and 0xFF).toLong() shl 8) or
            (raw[15].toInt() and 0xFF).toLong()

        if (raw.size < 80) return DecodeResult.Error(BleUpiError.TRUNCATED_PAYLOAD)
        val signature = raw.copyOfRange(16, 80)

        if (raw.size < 81) return DecodeResult.Error(BleUpiError.TRUNCATED_PAYLOAD)
        val txPower = raw[80]

        val header = PayloadHeader(
            protocolVersion = protocolVersion,
            mode = mode,
            dictionaryVersion = dictVersion,
            merchantShortHash = shortHash,
            vpaSuffixIndex = suffixIndex,
            amountPaise = amountPaise,
            nonce = nonce
        )

        var certOffset = 81

        val vpaSuffix: String = if (suffixIndex == 0) {
            if (raw.size < certOffset + 1) return DecodeResult.Error(BleUpiError.TRUNCATED_PAYLOAD)
            val suffixLen = raw[certOffset].toInt() and 0xFF
            certOffset += 1
            if (raw.size < certOffset + suffixLen) return DecodeResult.Error(BleUpiError.TRUNCATED_PAYLOAD)
            val suffix = String(raw, certOffset, suffixLen, Charsets.US_ASCII)
            certOffset += suffixLen
            suffix
        } else {
            CompressionDictionary.getHandle(suffixIndex)
                ?: return DecodeResult.Error(BleUpiError.CERT_MISSING_PROFILE)
        }

        if (raw.size < certOffset + 2) return DecodeResult.Error(BleUpiError.TRUNCATED_PAYLOAD)
        val (certMap, _) = decodeCborMap(raw, certOffset)

        val pk = (certMap[1] as? CborValue.Bytes)?.data
            ?: return DecodeResult.Error(BleUpiError.CERT_MISSING_PROFILE)
        val vpaFromCert = (certMap[2] as? CborValue.Text)?.text
            ?: return DecodeResult.Error(BleUpiError.CERT_MISSING_PROFILE)
        val displayName = (certMap[3] as? CborValue.Text)?.text
            ?: return DecodeResult.Error(BleUpiError.CERT_MISSING_PROFILE)
        val caSig = (certMap[4] as? CborValue.Bytes)?.data
            ?: return DecodeResult.Error(BleUpiError.CERT_INVALID)

        val vpa = if (suffixIndex != 0) {
            val prefix = vpaFromCert.split("@").firstOrNull() ?: ""
            "$prefix@$vpaSuffix"
        } else {
            vpaFromCert
        }

        return DecodeResult.Success(
            PaymentRequest(
                header = header,
                vpa = vpa,
                displayName = displayName,
                merchantPublicKey = pk,
                signature = signature,
                txPower = txPower,
                caSignature = caSig
            )
        )
    }
}

sealed class DecodeResult {
    data class Success(val request: PaymentRequest) : DecodeResult()
    data class Error(val error: BleUpiError) : DecodeResult()
}
