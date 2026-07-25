package com.bleupi.customer

import android.app.*
import android.bluetooth.BluetoothAdapter
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

    fun setListener(listener: BleUpiListener?) {
        this.listener = listener
        pendingRequest?.let { req ->
            pendingProfile?.let { profile ->
                listener?.onMerchantDetected(profile)
                listener?.onPaymentRequestReceived(req)
            }
        }
        pendingRequest = null
        pendingProfile = null
    }

    override fun onCreate() {
        super.onCreate()
        try {
            val notification = buildNotification("Scanning for nearby merchants...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            android.util.Log.e("BleUpi", "startForeground failed: ${e.message}")
        }
        startScanning()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    inner class LocalBinder : Binder() {
        fun getService(): BleUpiForegroundService = this@BleUpiForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    private fun startScanning() {
        android.util.Log.d("BleUpi", "startScanning()")
        scanner = DefaultBleUpiScanner(this)
        scanner?.start(object : BleUpiListener {
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
                val title = "Pay ${request.displayName}"
                val body = if (request.header.amountPaise > 0) {
                    "₹%.2f".format(request.header.amountPaise / 100.0)
                } else {
                    "Open amount — tap to pay"
                }
                updateNotification(title, body)
                listener?.onPaymentRequestReceived(request)
            }

            override fun onError(error: BleUpiError) {
                android.util.Log.e("BleUpi", "onError: $error")
                listener?.onError(error)
            }
        })
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BLE UPI Scanner",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BLE UPI")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(title: String, body: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        scanner?.stop()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "ble_upi_scanner"
        const val NOTIFICATION_ID = 1001
    }
}
