package com.bleupi.customer

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.bleupi.protocol.*

class MainActivity : AppCompatActivity() {
    private var service: BleUpiForegroundService? = null
    private var bound = false
    private var activeRequest: PaymentRequest? = null

    private lateinit var mainLayout: ConstraintLayout
    private lateinit var statusText: TextView
    private lateinit var paymentCard: View
    private lateinit var merchantNameText: TextView
    private lateinit var merchantVpaText: TextView
    private lateinit var amountText: TextView
    private lateinit var payButton: Button
    private lateinit var dismissButton: Button
    private lateinit var bannerLayout: View
    private lateinit var bannerText: TextView
    private lateinit var bannerButton: Button

    private lateinit var bluetoothMonitor: BluetoothStateMonitor

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (granted) {
            checkAndPromptBluetooth()
        } else {
            Toast.makeText(this, "Permissions required for BLE UPI", Toast.LENGTH_LONG).show()
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkAndPromptBluetooth()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as BleUpiForegroundService.LocalBinder).getService()
            bound = true
            service?.setListener(scannerListener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    private val scannerListener = object : BleUpiListener {
        override fun onMerchantDetected(merchant: MerchantProfile) {
            runOnUiThread {
                statusText.text = "Merchant nearby"
            }
        }

        override fun onMerchantLost(merchantId: String) {
            runOnUiThread {
                statusText.text = "Scanning..."
                paymentCard.visibility = View.GONE
                activeRequest = null
            }
        }

        override fun onPaymentRequestReceived(request: PaymentRequest) {
            runOnUiThread {
                activeRequest = request
                statusText.text = "Payment ready"
                merchantNameText.text = request.displayName
                merchantVpaText.text = request.vpa
                amountText.text = if (request.header.amountPaise > 0) {
                    "₹%.2f".format(request.header.amountPaise / 100.0)
                } else {
                    "Open amount"
                }
                paymentCard.visibility = View.VISIBLE
            }
        }

        override fun onError(error: BleUpiError) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Error: $error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainLayout = findViewById(R.id.main_layout)
        statusText = findViewById(R.id.status_text)
        paymentCard = findViewById(R.id.payment_card)
        merchantNameText = findViewById(R.id.merchant_name)
        merchantVpaText = findViewById(R.id.merchant_vpa)
        amountText = findViewById(R.id.amount)
        payButton = findViewById(R.id.pay_button)
        dismissButton = findViewById(R.id.dismiss_button)
        bannerLayout = findViewById(R.id.banner_layout)
        bannerText = findViewById(R.id.banner_text)
        bannerButton = findViewById(R.id.banner_button)

        paymentCard.visibility = View.GONE
        bannerLayout.visibility = View.GONE
        statusText.text = "Checking..."

        payButton.setOnClickListener {
            activeRequest?.let { request ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(request.upiUri))
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "No UPI app available", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dismissButton.setOnClickListener {
            paymentCard.visibility = View.GONE
            statusText.text = "Scanning..."
            activeRequest = null
        }

        bannerButton.setOnClickListener {
            resolveNextBlocker()
        }

        bluetoothMonitor = BluetoothStateMonitor(this)
        bluetoothMonitor.register(object : BluetoothStateMonitor.Callback {
            override fun onBluetoothStateChanged(enabled: Boolean) {
                runOnUiThread {
                    if (!enabled) {
                        showBanner("Bluetooth is off", "Turn On") { promptEnableBluetooth() }
                    } else {
                        checkAndPromptBluetooth()
                    }
                }
            }
        })

        findViewById<View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        checkAndPromptBluetooth()
    }

    private fun promptEnableBluetooth() {
        val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        bluetoothEnableLauncher.launch(enableIntent)
    }

    private fun checkAndPromptBluetooth() {
        if (!bluetoothMonitor.isBluetoothEnabled()) {
            showBanner("Bluetooth is off", "Turn On") { promptEnableBluetooth() }
            return
        }
        checkPermissions()
    }

    private fun checkPermissions() {
        val needed = PermissionHelper.getCustomerPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            hideBanner()
            if (!bound) startScanning()
        } else {
            val label = when (needed.first()) {
                Manifest.permission.ACCESS_FINE_LOCATION -> "Location"
                Manifest.permission.BLUETOOTH_SCAN -> "Nearby devices"
                Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth"
                Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
                else -> "Permissions"
            }
            showBanner("$label permission needed", "Grant") {
                permissionLauncher.launch(needed.toTypedArray())
            }
        }
    }

    private fun resolveNextBlocker() {
        if (!bluetoothMonitor.isBluetoothEnabled()) {
            showBanner("Bluetooth is off", "Turn On") { promptEnableBluetooth() }
            return
        }
        checkPermissions()
    }

    private fun showBanner(message: String, action: String, onClick: () -> Unit) {
        bannerText.text = message
        bannerButton.text = action
        bannerButton.setOnClickListener { onClick() }
        bannerLayout.visibility = View.VISIBLE
        statusText.text = "Waiting..."
    }

    private fun hideBanner() {
        bannerLayout.visibility = View.GONE
        statusText.text = "Scanning..."
    }

    private fun startScanning() {
        val serviceIntent = Intent(this, BleUpiForegroundService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onStart() {
        super.onStart()
        service?.setListener(scannerListener)
    }

    override fun onStop() {
        super.onStop()
        service?.setListener(null)
    }

    override fun onResume() {
        super.onResume()
        if (!bluetoothMonitor.isBluetoothEnabled()) {
            showBanner("Bluetooth is off", "Turn On") { promptEnableBluetooth() }
        } else {
            checkPermissions()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothMonitor.unregister()
        if (bound) unbindService(serviceConnection)
    }
}
