package com.example.blechat

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
        private const val REQUEST_ENABLE_BT = 102
        private const val OVERLAY_PERMISSION_REQ_CODE = 103
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvChatLog: TextView
    private lateinit var btnReset: Button
    private lateinit var scrollView: ScrollView

    // Payment request card views
    private lateinit var cardPayment: LinearLayout
    private lateinit var tvPayeeName: TextView
    private lateinit var tvPayeeVPA: TextView
    private lateinit var tvAmount: TextView
    private lateinit var tvNote: TextView
    private lateinit var btnPayNow: Button
    private lateinit var layoutPlaceholder: LinearLayout

    private var currentUpiUri: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind layout views
        tvStatus = findViewById(R.id.tvStatus)
        tvChatLog = findViewById(R.id.tvChatLog)
        btnReset = findViewById(R.id.btnReset)
        scrollView = findViewById(R.id.scrollView)

        // Bind payment card views
        cardPayment = findViewById(R.id.cardPayment)
        tvPayeeName = findViewById(R.id.tvPayeeName)
        tvPayeeVPA = findViewById(R.id.tvPayeeVPA)
        tvAmount = findViewById(R.id.tvAmount)
        tvNote = findViewById(R.id.tvNote)
        btnPayNow = findViewById(R.id.btnPayNow)
        layoutPlaceholder = findViewById(R.id.layoutPlaceholder)

        tvChatLog.movementMethod = ScrollingMovementMethod()

        btnReset.setOnClickListener {
            resetBluetoothService()
        }

        btnPayNow.setOnClickListener {
            currentUpiUri?.let { uriString ->
                launchPaymentIntent(uriString)
            }
        }

        appendStatus("App started. Checking permissions...")
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissionsList = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsList.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissionsList.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissionsList.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsList.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissionsList.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            appendStatus("Requesting BLE permissions...")
            requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            checkBluetoothAndStart()
        }
    }

    private fun checkBluetoothAndStart() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            appendStatus("Error: Bluetooth not supported on this device.")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            appendStatus("Bluetooth is disabled. Requesting user to enable...")
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            try {
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            } catch (e: SecurityException) {
                appendStatus("Error: Bluetooth permission denied when trying to enable BT.")
            }
        } else {
            checkOverlayPermissionAndStart()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                appendStatus("BLE Permissions granted.")
                checkBluetoothAndStart()
            } else {
                appendStatus("Error: BLE Permissions denied. Background receiver cannot start.")
            }
        }
    }

    private fun checkOverlayPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            appendStatus("Overlay permission required for background payment triggers.")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
        } else {
            startUpiService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == Activity.RESULT_OK) {
                appendStatus("Bluetooth enabled successfully.")
                checkOverlayPermissionAndStart()
            } else {
                appendStatus("Warning: Bluetooth is required for this app to work. Please enable it.")
            }
        } else if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                appendStatus("Overlay permission granted.")
                startUpiService()
            } else {
                appendStatus("Warning: Overlay permission denied. Payments will not pop open automatically when in background.")
                startUpiService()
            }
        }
    }

    private fun startUpiService() {
        appendStatus("Starting background receiver service...")
        val serviceIntent = Intent(this, BleUpiService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun resetBluetoothService() {
        appendStatus("Resetting background receiver...")
        stopService(Intent(this, BleUpiService::class.java))
        
        // Clear UI state
        layoutPlaceholder.visibility = View.VISIBLE
        cardPayment.visibility = View.GONE
        currentUpiUri = null

        // Restart service after 1 second delay
        scrollView.postDelayed({
            startUpiService()
        }, 1000)
    }

    private fun launchPaymentIntent(upiString: String) {
        android.util.Log.i("BLEChat", "Manual launching payment intent: $upiString")
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(upiString)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            appendStatus("Payment intent triggered manually.")
        } catch (e: Exception) {
            android.util.Log.e("BLEChat", "Failed to launch intent: ${e.message}", e)
            appendStatus("Error: No installed UPI payment apps found.")
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_OFF) {
                    appendStatus("Bluetooth was turned off. Requesting user to enable...")
                    checkBluetoothAndStart()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        
        // Register receiver for Bluetooth state changes
        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        
        // Attach listener to service to receive live UI updates
        BleUpiService.listener = object : BleUpiService.BleServiceListener {
            override fun onStatusUpdated(status: String) {
                runOnUiThread {
                    appendStatus(status)
                }
            }

            override fun onLogUpdated(log: String) {
                runOnUiThread {
                    appendChatMessage(log)
                }
            }

            override fun onPaymentRequestReceived(
                payeeName: String,
                vpa: String,
                amount: String,
                note: String,
                rawUri: String
            ) {
                runOnUiThread {
                    currentUpiUri = rawUri
                    layoutPlaceholder.visibility = View.GONE
                    cardPayment.visibility = View.VISIBLE
                    
                    tvPayeeName.text = payeeName
                    tvPayeeVPA.text = "VPA: $vpa"
                    
                    if (amount.isNotEmpty()) {
                        tvAmount.text = "₹$amount"
                    } else {
                        tvAmount.text = "Flexible (Enter in App)"
                    }
                    
                    if (note.isNotEmpty()) {
                        tvNote.text = "Note: $note"
                    } else {
                        tvNote.text = "Note: Set by Customer"
                    }
                }
            }

            override fun onResetUI() {
                runOnUiThread {
                    layoutPlaceholder.visibility = View.VISIBLE
                    cardPayment.visibility = View.GONE
                    currentUpiUri = null
                }
            }
        }

        if (BleUpiService.isServiceRunning) {
            appendStatus("Attached to running background receiver.")
        }
    }

    override fun onPause() {
        super.onPause()
        
        // Unregister receiver
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (e: Exception) {
            // ignore
        }
        
        // Detach listener to prevent memory leaks when app minimized
        BleUpiService.listener = null
    }

    private fun appendStatus(status: String) {
        android.util.Log.i("BLEChat", "Status: $status")
        tvStatus.text = status
        tvChatLog.append("[System] $status\n")
        scrollToBottom()
    }

    private fun appendChatMessage(msg: String) {
        android.util.Log.i("BLEChat", "Log: $msg")
        tvChatLog.append("$msg\n")
        scrollToBottom()
    }

    private fun scrollToBottom() {
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }
}
