package com.bleupi.protocol

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Handler
import android.os.Looper

class ChunkAssembly(private val totalChunks: Int) {
    private val chunks = arrayOfNulls<ByteArray>(totalChunks)
    private var received = 0

    fun addChunk(index: Int, data: ByteArray): Boolean {
        if (index < 0 || index >= totalChunks) return false
        if (chunks[index] != null) return received == totalChunks
        chunks[index] = data
        received++
        return received == totalChunks
    }

    fun peekChunk0(): ByteArray? = chunks[0]

    fun isComplete(): Boolean = received == totalChunks

    fun reset() {
        for (i in chunks.indices) chunks[i] = null
        received = 0
    }

    fun assemble(): ByteArray {
        val totalSize = chunks.sumOf { it?.size ?: 0 }
        val result = ByteArray(totalSize)
        var offset = 0
        for (chunk in chunks) {
            if (chunk != null) {
                System.arraycopy(chunk, 0, result, offset, chunk.size)
                offset += chunk.size
            }
        }
        return result
    }
}

class DefaultBleUpiScanner(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
) : BleUpiScanner {

    private val bleScanner = BleScanner(bluetoothAdapter)
    private val profileCache = MerchantProfileCache(context)
    private val cooldown = CooldownManager(windowDurationSeconds = 180)
    private val rssiFilters = HashMap<String, RssiFilter>()
    private val chunkBuffers = HashMap<Int, ChunkAssembly>()
    private val mainHandler = Handler(Looper.getMainLooper()!!)

    private var listener: BleUpiListener? = null
    private var scanning = false

    override fun start(listener: BleUpiListener) {
        if (scanning) return
        this.listener = listener
        scanning = true
        bleScanner.startScan(object : BleScanner.Callback {
            override fun onScanResult(scanResult: android.bluetooth.le.ScanResult) {
                handleScanResult(scanResult)
            }

            override fun onScanFailed(errorCode: Int) {
                mainHandler.post {
                    listener.onError(BleUpiError.TRUNCATED_PAYLOAD)
                }
            }
        })
    }

    override fun stop() {
        scanning = false
        bleScanner.stopScan()
        listener = null
    }

    override fun isScanning(): Boolean = scanning

    private fun handleScanResult(scanResult: android.bluetooth.le.ScanResult) {
        val data = scanResult.scanRecord?.getManufacturerSpecificData(BleScanner.MANUFACTURER_ID) ?: return
        if (data.isEmpty()) return

        var rawPayload = data

        // Check if chunked payload (multi-frame mode byte 0 header)
        val firstByte = data[0].toInt() and 0xFF
        val chunkIdx = (firstByte shr 4) and 0x0F
        val totalChunks = firstByte and 0x0F

        if (totalChunks > 1 && totalChunks <= 15 && chunkIdx < totalChunks) {
            val chunkData = data.copyOfRange(1, data.size)

            if (chunkIdx == 0) {
                // Always reset the buffer when chunk 0 arrives — new cycle.
                if (chunkData.size < 6) return
                val shortHash = ((chunkData[2].toInt() and 0xFF) shl 24) or
                    ((chunkData[3].toInt() and 0xFF) shl 16) or
                    ((chunkData[4].toInt() and 0xFF) shl 8) or
                    (chunkData[5].toInt() and 0xFF)

                val existing = chunkBuffers[shortHash]
                if (existing == null) {
                    val assembly = ChunkAssembly(totalChunks)
                    chunkBuffers[shortHash] = assembly
                    assembly.addChunk(0, chunkData)
                } else {
                    // Reset and reseed — cycle restart
                    existing.reset()
                    existing.addChunk(0, chunkData)
                }
            } else {
                // Subsequent chunks: append to the matching active buffer
                if (chunkBuffers.isEmpty()) return
                val activeAssembly = chunkBuffers.values.firstOrNull { it.peekChunk0() != null }
                    ?: return
                val complete = activeAssembly.addChunk(chunkIdx, chunkData)
                if (complete) {
                    rawPayload = activeAssembly.assemble()
                    chunkBuffers.entries.removeAll { it.value === activeAssembly }
                }
            }
        } else {
            rawPayload = data
        }

        val decodeResult = PayloadDecoder.decode(rawPayload)
        if (decodeResult !is DecodeResult.Success) return
        val request = decodeResult.request

        if (!cooldown.shouldAllow(request.header.merchantShortHash, request.header.nonce)) return

        if (!CryptoVerifier.verifyNonce(request.header.nonce)) {
            mainHandler.post { listener?.onError(BleUpiError.EXPIRED_NONCE) }
            return
        }

        if (!CryptoVerifier.verifyPayload(request)) {
            mainHandler.post { listener?.onError(BleUpiError.SIGNATURE_INVALID) }
            return
        }

        val deviceId = scanResult.device.address

        val rssiFilter = rssiFilters.getOrPut(deviceId) { RssiFilter() }
        rssiFilter.update(scanResult.rssi)

        when (rssiFilter.classifyProximity(request.txPower)) {
            RssiFilter.Proximity.NEAR -> {
                val profile = resolveProfile(request)
                mainHandler.post {
                    listener?.onMerchantDetected(profile)
                    listener?.onPaymentRequestReceived(request)
                }
            }
            RssiFilter.Proximity.FAR -> {
                val shortHashStr = request.header.merchantShortHash.toString(16)
                mainHandler.post { listener?.onMerchantLost(shortHashStr) }
            }
            RssiFilter.Proximity.UNKNOWN -> {}
        }
    }

    private fun resolveProfile(request: PaymentRequest): MerchantProfile {
        val cached = profileCache.get(request.header.merchantShortHash)
        if (cached != null) return cached

        val ok = CryptoVerifier.verifyCertificate(
            publicKey = request.merchantPublicKey,
            vpa = request.vpa,
            displayName = request.displayName,
            caSignature = request.caSignature,
            rootCaPublicKey = DevRootCa.publicKey
        )
        if (!ok) {
            mainHandler.post { listener?.onError(BleUpiError.CERT_INVALID) }
            return MerchantProfile(
                shortHash = request.header.merchantShortHash,
                displayName = request.displayName,
                vpa = request.vpa,
                publicKey = request.merchantPublicKey
            )
        }

        val profile = MerchantProfile(
            shortHash = request.header.merchantShortHash,
            displayName = request.displayName,
            vpa = request.vpa,
            publicKey = request.merchantPublicKey
        )
        profileCache.put(profile)
        return profile
    }
}
