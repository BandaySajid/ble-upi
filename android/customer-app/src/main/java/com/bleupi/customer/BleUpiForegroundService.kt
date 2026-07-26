package com.bleupi.customer

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bleupi.protocol.*

class BleUpiForegroundService : Service() {
    private var scanner: DefaultBleUpiScanner? = null
    private var listener: BleUpiListener? = null
    private var pendingRequest: PaymentRequest? = null
    private var pendingProfile: MerchantProfile? = null
    private var lastSeenRequest: PaymentRequest? = null
    private var notifiedNonceKey: String? = null

    private var scannerListener: BleUpiListener? = null

    fun setListener(listener: BleUpiListener?) {
        this.listener = listener
        if (listener != null) {
            pendingRequest?.let { req ->
                pendingProfile?.let { profile ->
                    listener.onMerchantDetected(profile)
                    listener.onPaymentRequestReceived(req)
                }
            }
            pendingRequest = null
            pendingProfile = null

            replayLastSeenIfNear(listener)
        }
    }

    private fun replayLastSeenIfNear(listener: BleUpiListener) {
        val request = lastSeenRequest ?: return
        val s = scanner ?: return
        val nearDeviceId = s.lastNearDeviceId ?: return
        if (s.isDeviceNear(nearDeviceId)) {
            android.util.Log.d("BleUpi", "Replaying lastSeenRequest: ${request.vpa}")
            listener.onPaymentRequestReceived(request)
        }
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("BleUpi", "ForegroundService onCreate()")
        try {
            val notification = buildNotification("Scanning for nearby merchants...", PRIORITY_LOW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            android.util.Log.d("BleUpi", "startForeground OK")
        } catch (e: Exception) {
            android.util.Log.e("BleUpi", "startForeground failed: ${e.message}", e)
        }
        startScanning()
        BleUpiWorker.schedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("BleUpi", "ForegroundService onStartCommand()")
        return START_STICKY
    }

    inner class LocalBinder : Binder() {
        fun getService(): BleUpiForegroundService = this@BleUpiForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    private fun startScanning() {
        android.util.Log.d("BleUpi", "startScanning()")
        scanner = DefaultBleUpiScanner(this)
        scannerListener = object : BleUpiListener {
            override fun onMerchantDetected(merchant: MerchantProfile) {
                android.util.Log.d("BleUpi", "onMerchantDetected: ${merchant.displayName}")
                pendingProfile = merchant
                listener?.onMerchantDetected(merchant)
            }

            override fun onMerchantLost(merchantId: String) {
                android.util.Log.d("BleUpi", "onMerchantLost: $merchantId")
                listener?.onMerchantLost(merchantId)
                pendingProfile = null
                pendingRequest = null
            }

            override fun onPaymentRequestReceived(request: PaymentRequest) {
                android.util.Log.d("BleUpi", "onPaymentRequestReceived: ${request.vpa} ${request.header.amountPaise}")
                pendingRequest = request
                lastSeenRequest = request

                val nonceKey = "${request.header.merchantShortHash}:${request.header.nonce}"
                val shouldNotify = notifiedNonceKey != nonceKey

                if (shouldNotify && isNotificationEnabled()) {
                    notifiedNonceKey = nonceKey
                    val title = "Pay ${request.displayName}"
                    val body = if (request.header.amountPaise > 0) {
                        "₹%.2f".format(request.header.amountPaise / 100.0)
                    } else {
                        "Open amount — tap to pay"
                    }
                    updateNotification(title, body, PRIORITY_DEFAULT)
                }

                listener?.onPaymentRequestReceived(request)
            }

            override fun onError(error: BleUpiError) {
                android.util.Log.e("BleUpi", "onError: $error")
                listener?.onError(error)
            }
        }
        scanner?.start(scannerListener!!)
    }

    private fun buildNotification(text: String, priority: Int): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BLE UPI Scanner",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)

            val paymentChannel = NotificationChannel(
                PAYMENT_CHANNEL_ID,
                "Payment Requests",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications when a nearby merchant requests payment"
            }
            manager.createNotificationChannel(paymentChannel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BLE UPI")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .setPriority(priority)
            .build()
    }

    private fun updateNotification(title: String, body: String, priority: Int) {
        val channelId = if (priority >= PRIORITY_DEFAULT) PAYMENT_CHANNEL_ID else CHANNEL_ID
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .setPriority(priority)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun isNotificationEnabled(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getBoolean(KEY_NOTIFY_NEARBY, true)
    }

    override fun onDestroy() {
        scanner?.stop()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "ble_upi_scanner"
        const val PAYMENT_CHANNEL_ID = "ble_upi_payments"
        const val NOTIFICATION_ID = 1001
        const val PREFS_NAME = "ble_upi_settings"
        const val KEY_NOTIFY_NEARBY = "notify_nearby"
        const val PRIORITY_LOW = NotificationCompat.PRIORITY_LOW
        const val PRIORITY_DEFAULT = NotificationCompat.PRIORITY_DEFAULT
    }
}
