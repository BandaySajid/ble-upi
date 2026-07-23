package com.example.blechat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import java.nio.charset.StandardCharsets
import java.util.UUID

class BleUpiService : Service() {

    interface BleServiceListener {
        fun onStatusUpdated(status: String)
        fun onLogUpdated(log: String)
        fun onPaymentRequestReceived(
            payeeName: String,
            vpa: String,
            amount: String,
            note: String,
            rawUri: String
        )
        fun onResetUI()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val PAYMENT_NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "BleUpiServiceChannel"
        private const val PAYMENT_CHANNEL_ID = "BleUpiPaymentChannel"

        private val SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val RX_CHAR_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val TX_CHAR_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD_UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        var isServiceRunning = false
            private set

        data class PaymentRequest(
            val payeeName: String,
            val vpa: String,
            val amount: String,
            val note: String,
            val rawUri: String
        )

        var lastPaymentRequest: PaymentRequest? = null

        var listener: BleServiceListener? = null
            set(value) {
                field = value
                if (value != null) {
                    lastPaymentRequest?.let { req ->
                        value.onPaymentRequestReceived(req.payeeName, req.vpa, req.amount, req.note, req.rawUri)
                    }
                }
            }
    }

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    private val subscribedDevices = mutableSetOf<BluetoothDevice>()
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    private var isAdvertising = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wasBtEnabled = false

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val btEnabled = bluetoothAdapter?.isEnabled == true
            if (!btEnabled && wasBtEnabled) {
                android.util.Log.i("BLEChat", "Watchdog: Bluetooth turned OFF. Tearing down.")
                isAdvertising = false
                connectedDevices.clear()
                subscribedDevices.clear()
                try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
                try { bluetoothGattServer?.close() } catch (_: Exception) {}
                bluetoothGattServer = null
                advertiser = null
                lastPaymentRequest = null
                mainHandler.post { listener?.onResetUI() }
                cancelPaymentNotification()
                updateStatus("Bluetooth off. Waiting...")
            } else if (btEnabled && !wasBtEnabled) {
                android.util.Log.i("BLEChat", "Watchdog: Bluetooth turned ON. Rebuilding in 500ms...")
                mainHandler.postDelayed({
                    setupGattServer()
                    startAdvertising()
                }, 500)
            } else if (btEnabled) {
                if (connectedDevices.isEmpty() && !isAdvertising && lastPaymentRequest == null) {
                    android.util.Log.i("BLEChat", "Watchdog: Not advertising and no connections. Restarting ads.")
                    startAdvertising()
                }
                if (bluetoothGattServer == null) {
                    android.util.Log.i("BLEChat", "Watchdog: GATT server is null. Rebuilding.")
                    setupGattServer()
                }
            }
            wasBtEnabled = btEnabled
            mainHandler.postDelayed(this, 3000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        wasBtEnabled = bluetoothAdapter?.isEnabled == true

        startForegroundNotification()
        setupGattServer()
        startAdvertising()
        mainHandler.postDelayed(watchdogRunnable, 3000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (bluetoothGattServer == null && bluetoothAdapter?.isEnabled == true) {
            setupGattServer()
        }
        if (!isAdvertising && bluetoothAdapter?.isEnabled == true) {
            startAdvertising()
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "BLE Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            
            val pChannel = NotificationChannel(PAYMENT_CHANNEL_ID, "UPI Payments", NotificationManager.IMPORTANCE_HIGH)
            pChannel.enableVibration(true)
            getSystemService(NotificationManager::class.java).createNotificationChannel(pChannel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("BLE UPI Terminal")
            .setContentText("Broadcasting to Mac...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                android.util.Log.i("BLEChat", "Device connected: ${device.address}")
                connectedDevices.add(device)
                updateStatus("Connected to Mac terminal")
                // Keep advertising so MAC address doesn't rotate on reconnect
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                android.util.Log.i("BLEChat", "Device disconnected: ${device.address}")
                connectedDevices.remove(device)
                subscribedDevices.remove(device)
                updateStatus("Disconnected. Waiting for Mac...")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (characteristic.uuid == RX_CHAR_UUID) {
                if (responseNeeded) bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                value?.let {
                    val receivedStr = String(it, StandardCharsets.UTF_8)
                    android.util.Log.i("BLEChat", "Received: $receivedStr")
                    if (receivedStr.startsWith("upi://")) {
                        handleIncomingUpi(receivedStr)
                        sendNotificationToMac("STATUS:NOTIFIED")
                    }
                }
            } else {
                if (responseNeeded) bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == TX_CHAR_UUID) {
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, "STATUS:OK".toByteArray())
            } else {
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    subscribedDevices.add(device)
                } else if (value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    subscribedDevices.remove(device)
                }
                if (responseNeeded) bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    private fun setupGattServer() {
        if (bluetoothGattServer != null) return
        val server = bluetoothManager?.openGattServer(this, gattServerCallback)
        if (server == null) {
            updateStatus("Waiting for Bluetooth hardware...")
            return
        }
        bluetoothGattServer = server
        server.clearServices() // Ensure clean state before registering service

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        rxCharacteristic = BluetoothGattCharacteristic(
            RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        txCharacteristic = BluetoothGattCharacteristic(
            TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val cccd = BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        txCharacteristic?.addDescriptor(cccd)

        service.addCharacteristic(rxCharacteristic)
        service.addCharacteristic(txCharacteristic)
        server.addService(service)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            isAdvertising = true
            updateStatus("Advertising: Listening for Mac...")
        }
        override fun onStartFailure(errorCode: Int) {
            if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) {
                isAdvertising = true
                updateStatus("Advertising: Listening for Mac...")
            } else {
                isAdvertising = false
                updateStatus("Advertising failed: $errorCode")
            }
        }
    }

    private fun startAdvertising() {
        if (isAdvertising || bluetoothAdapter?.isEnabled != true) return
        val adv = bluetoothAdapter?.bluetoothLeAdvertiser ?: return
        
        // Stop any legacy sessions using this callback to prevent error 3
        try { adv.stopAdvertising(advertiseCallback) } catch (_: Exception) {}

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        
        try {
            isAdvertising = true
            adv.startAdvertising(settings, data, null, advertiseCallback)
        } catch (e: Exception) {
            isAdvertising = false
        }
    }

    private fun stopAdvertising() {
        if (!isAdvertising) return
        try { bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) } catch (e: Exception) {}
        isAdvertising = false
    }

    private fun handleIncomingUpi(upiString: String) {
        val uri = Uri.parse(upiString)
        val payeeName = uri.getQueryParameter("pn") ?: "Merchant"
        val vpa = uri.getQueryParameter("pa") ?: "Unknown"

        lastPaymentRequest = PaymentRequest(payeeName, vpa, "", "", upiString)
        mainHandler.post { listener?.onPaymentRequestReceived(payeeName, vpa, "", "", upiString) }
        showPaymentNotification(payeeName, vpa, upiString)
        
        // Stop advertising immediately to prevent the Mac from connecting again
        stopAdvertising()
    }

    private fun showPaymentNotification(payeeName: String, vpa: String, upiUrl: String) {
        val payIntent = Intent(Intent.ACTION_VIEW, Uri.parse(upiUrl)).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        val payPending = PendingIntent.getActivity(this, 0, payIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = Notification.Builder(this, PAYMENT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Pay $payeeName")
            .setContentText("Tap to open UPI payment • $vpa")
            .setContentIntent(payPending)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        getSystemService(NotificationManager::class.java).notify(PAYMENT_NOTIFICATION_ID, notification)
    }
    
    private fun cancelPaymentNotification() {
        try { getSystemService(NotificationManager::class.java).cancel(PAYMENT_NOTIFICATION_ID) } catch (e: Exception) {}
    }

    private fun sendNotificationToMac(message: String) {
        txCharacteristic?.let { char ->
            char.value = message.toByteArray(StandardCharsets.UTF_8)
            subscribedDevices.forEach { dev -> bluetoothGattServer?.notifyCharacteristicChanged(dev, char, false) }
        }
    }

    fun resetServiceState() {
        stopAdvertising()
        connectedDevices.clear()
        subscribedDevices.clear()
        lastPaymentRequest = null
        mainHandler.post { listener?.onResetUI() }
        cancelPaymentNotification()
        
        // Restart advertising cleanly
        mainHandler.postDelayed({ 
            if (bluetoothAdapter?.isEnabled == true) {
                startAdvertising()
            }
        }, 500)
    }

    private fun updateStatus(status: String) { mainHandler.post { listener?.onStatusUpdated(status) } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        mainHandler.removeCallbacks(watchdogRunnable)
        stopAdvertising()
        try { bluetoothGattServer?.close() } catch (e: Exception) {}
    }
}
