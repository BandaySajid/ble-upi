package com.bleupi.protocol

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Handler
import android.os.Looper

class DefaultBleUpiScanner(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
) : BleUpiScanner {

    private val bleScanner = BleScanner(bluetoothAdapter)
    private val profileCache = MerchantProfileCache(context)
    private val cooldown = CooldownManager(windowDurationSeconds = 180)
    private val rssiFilters = HashMap<String, RssiFilter>()
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
                    listener.onError(BleUpiError.TRUNCTED_PAYLOAD)
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
        val data = scanResult.scanRecord?.bytes ?: return
        if (data.isEmpty()) return

        val decodeResult = PayloadDecoder.decode(data)
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
                val merchantId = request.header.merchantShortHash.toString(16)
                mainHandler.post { listener?.onMerchantLost(merchantId) }
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
