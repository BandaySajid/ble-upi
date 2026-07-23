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
import androidx.core.content.ContextCompat
import com.bleupi.protocol.*

class MainActivity : AppCompatActivity() {
    private var service: BleUpiForegroundService? = null
    private var bound = false
    private var activeRequest: PaymentRequest? = null

    private lateinit var statusText: TextView
    private lateinit var paymentCard: View
    private lateinit var merchantNameText: TextView
    private lateinit var merchantVpaText: TextView
    private lateinit var amountText: TextView
    private lateinit var payButton: Button
    private lateinit var dismissButton: Button

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (granted) {
            startScanning()
        } else {
            Toast.makeText(this, "Bluetooth and Location permissions required", Toast.LENGTH_LONG).show()
        }
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

        statusText = findViewById(R.id.status_text)
        paymentCard = findViewById(R.id.payment_card)
        merchantNameText = findViewById(R.id.merchant_name)
        merchantVpaText = findViewById(R.id.merchant_vpa)
        amountText = findViewById(R.id.amount)
        payButton = findViewById(R.id.pay_button)
        dismissButton = findViewById(R.id.dismiss_button)

        paymentCard.visibility = View.GONE
        statusText.text = "Scanning..."

        payButton.setOnClickListener {
            activeRequest?.let { request ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(request.upiUri))
                intent.setPackage("com.google.android.apps.nbu.paisa.user") // default to Google Pay
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(request.upiUri))
                    startActivity(fallback)
                }
            }
        }

        dismissButton.setOnClickListener {
            paymentCard.visibility = View.GONE
            statusText.text = "Scanning..."
            activeRequest = null
        }

        checkPermissions()
    }

    private fun checkPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) {
            startScanning()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
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

    override fun onDestroy() {
        super.onDestroy()
        if (bound) unbindService(serviceConnection)
    }
}
