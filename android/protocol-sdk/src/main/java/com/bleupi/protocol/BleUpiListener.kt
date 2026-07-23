package com.bleupi.protocol

interface BleUpiListener {
    fun onMerchantDetected(merchant: MerchantProfile)
    fun onMerchantLost(merchantId: String)
    fun onPaymentRequestReceived(request: PaymentRequest)
    fun onError(error: BleUpiError)
}
