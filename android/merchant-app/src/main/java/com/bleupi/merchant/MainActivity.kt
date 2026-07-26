package com.bleupi.merchant

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.bleupi.protocol.*

class MainActivity : AppCompatActivity() {
    private lateinit var mainLayout: ConstraintLayout
    private lateinit var vpaInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var amountInput: EditText
    private lateinit var broadcastButton: Button
    private lateinit var dashboardButton: Button
    private lateinit var statusText: TextView
    private lateinit var elapsedText: TextView
    private lateinit var bannerLayout: View
    private lateinit var bannerText: TextView
    private lateinit var bannerButton: Button

    private var advertiser: BluetoothLeAdvertiser? = null
    private var broadcasting = false
    private var broadcastStartTime: Long = 0
    private var chunkIndex = 0
    private var chunks: List<ByteArray> = emptyList()
    private var fullPayload: ByteArray? = null
    private var merchantPublicKey: ByteArray? = null
    private var merchantPrivateKey: ByteArray? = null
    private var useExtendedAdvertising = false

    private lateinit var bluetoothMonitor: BluetoothStateMonitor

    private val mainHandler = Handler(Looper.getMainLooper()!!)
    private val chunkInterval = 30L
    private val maxChunkPayloadSize = 24

    private val chunkRunnable = object : Runnable {
        override fun run() {
            if (!broadcasting || chunks.isEmpty() || useExtendedAdvertising) return
            broadcastLegacyChunk(chunks[chunkIndex % chunks.size])
            chunkIndex++
            mainHandler.postDelayed(this, chunkInterval)
        }
    }
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (broadcasting) {
                val elapsed = (System.currentTimeMillis() - broadcastStartTime) / 1000
                elapsedText.text = "Elapsed: ${elapsed}s"
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            checkAndPromptBluetooth()
        } else {
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show()
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkAndPromptBluetooth()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainLayout = findViewById(R.id.main_layout)
        vpaInput = findViewById(R.id.vpa_input)
        nameInput = findViewById(R.id.name_input)
        amountInput = findViewById(R.id.amount_input)
        broadcastButton = findViewById(R.id.broadcast_button)
        dashboardButton = findViewById(R.id.dashboard_button)
        statusText = findViewById(R.id.status_text)
        elapsedText = findViewById(R.id.elapsed_text)
        bannerLayout = findViewById(R.id.banner_layout)
        bannerText = findViewById(R.id.banner_text)
        bannerButton = findViewById(R.id.banner_button)

        bannerLayout.visibility = View.GONE

        val prefs = getSharedPreferences("merchant", MODE_PRIVATE)
        vpaInput.setText(prefs.getString("vpa", ""))
        nameInput.setText(prefs.getString("name", ""))

        loadOrGenerateKeypair(prefs)

        broadcastButton.setOnClickListener {
            if (broadcasting) {
                stopBroadcasting()
            } else {
                checkPermissionsAndStart()
            }
        }

        dashboardButton.setOnClickListener {
            startActivity(Intent(this, PodDashboardActivity::class.java))
        }

        bannerButton.setOnClickListener {
            resolveNextBlocker()
        }

        bluetoothMonitor = BluetoothStateMonitor(this)
        bluetoothMonitor.register(object : BluetoothStateMonitor.Callback {
            override fun onBluetoothStateChanged(enabled: Boolean) {
                runOnUiThread {
                    if (broadcasting && !enabled) {
                        stopBroadcasting()
                        Toast.makeText(this@MainActivity, "Bluetooth turned off", Toast.LENGTH_SHORT).show()
                    }
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
        checkPermissionsAndStart()
    }

    private fun resolveNextBlocker() {
        if (!bluetoothMonitor.isBluetoothEnabled()) {
            showBanner("Bluetooth is off", "Turn On") { promptEnableBluetooth() }
            return
        }
        checkPermissionsAndStart()
    }

    private fun showBanner(message: String, action: String, onClick: () -> Unit) {
        bannerText.text = message
        bannerButton.text = action
        bannerButton.setOnClickListener { onClick() }
        bannerLayout.visibility = View.VISIBLE
    }

    private fun hideBanner() {
        bannerLayout.visibility = View.GONE
    }

    private fun loadOrGenerateKeypair(prefs: SharedPreferences) {
        val kp = Ed25519Keypair.generate()
        merchantPublicKey = kp.publicKey
        merchantPrivateKey = kp.privateKey

        val savedVpa = vpaInput.text.toString().trim()
        val savedName = nameInput.text.toString().trim()
        if (savedVpa.isNotEmpty()) prefs.edit().putString("vpa", savedVpa).apply()
        if (savedName.isNotEmpty()) prefs.edit().putString("name", savedName).apply()
    }

    private fun checkPermissionsAndStart() {
        val needed = PermissionHelper.getMerchantPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            hideBanner()
            startBroadcasting()
        } else {
            val label = when (needed.first()) {
                Manifest.permission.ACCESS_FINE_LOCATION -> "Location"
                Manifest.permission.BLUETOOTH_ADVERTISE -> "Bluetooth advertising"
                Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth"
                else -> "Permissions"
            }
            showBanner("$label permission needed", "Grant") {
                permissionLauncher.launch(needed.toTypedArray())
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startBroadcasting() {
        if (broadcasting) return

        val vpa = vpaInput.text.toString().trim()
        val name = nameInput.text.toString().trim()
        val amountStr = amountInput.text.toString().trim()
        val amount = if (amountStr.isNotEmpty()) (amountStr.toDouble() * 100).toLong() else 0L

        if (vpa.isEmpty() || name.isEmpty()) {
            Toast.makeText(this, "Enter VPA and name", Toast.LENGTH_SHORT).show()
            return
        }

        val pk = merchantPublicKey ?: return
        val sk = merchantPrivateKey ?: return

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Bluetooth is disabled", Toast.LENGTH_SHORT).show()
            return
        }

        advertiser = adapter.bluetoothLeAdvertiser
        val caPrivateKey = DevRootCa.privateKey

        useExtendedAdvertising = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            BleScanner.supportsExtendedAdvertising()

        if (useExtendedAdvertising) {
            fullPayload = PayloadEncoder.encode(
                vpa, name, amount, pk, sk, 0, caPrivateKey
            )
            android.util.Log.d("BleUpi", "Merchant using extended advertising, payload=${fullPayload!!.size} bytes")
            broadcastExtendedPayload(fullPayload!!)
        } else {
            chunks = PayloadEncoder.encodeMultiFrame(
                vpa = vpa,
                displayName = name,
                amountPaise = amount,
                merchantPublicKey = pk,
                merchantPrivateKey = sk,
                txPower = 0,
                caPrivateKey = caPrivateKey,
                maxChunkPayloadSize = maxChunkPayloadSize
            )
            android.util.Log.d("BleUpi", "Merchant using legacy multi-chunk, ${chunks.size} chunks")
            broadcastLegacyChunk(chunks[0])
            chunkIndex = 1
            mainHandler.postDelayed(chunkRunnable, chunkInterval)
        }

        broadcasting = true
        broadcastStartTime = System.currentTimeMillis()
        broadcastButton.text = "Stop Broadcasting"
        val modeLabel = if (useExtendedAdvertising) " (Extended)" else " (${chunks.size} frames)"
        statusText.text = "Broadcasting...$modeLabel"
        mainHandler.post(tickRunnable)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun broadcastExtendedPayload(payload: ByteArray) {
        try {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(false)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()

            val data = AdvertiseData.Builder()
                .addManufacturerData(0xFFFF, payload)
                .build()

            advertiser?.startAdvertising(settings, data, null, extendedAdvertiseCallback)
            android.util.Log.d("BleUpi", "Extended advertising started")
        } catch (e: Exception) {
            android.util.Log.w("BleUpi", "Extended advertising failed, falling back to legacy: ${e.message}")
            useExtendedAdvertising = false
            chunks = PayloadEncoder.encodeMultiFrame(
                vpa = vpaInput.text.toString().trim(),
                displayName = nameInput.text.toString().trim(),
                amountPaise = amountInput.text.toString().trim().let { if (it.isNotEmpty()) (it.toDouble() * 100).toLong() else 0L },
                merchantPublicKey = merchantPublicKey!!,
                merchantPrivateKey = merchantPrivateKey!!,
                txPower = 0,
                caPrivateKey = DevRootCa.privateKey,
                maxChunkPayloadSize = maxChunkPayloadSize
            )
            broadcastLegacyChunk(chunks[0])
            chunkIndex = 1
            mainHandler.postDelayed(chunkRunnable, chunkInterval)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun broadcastLegacyChunk(chunk: ByteArray) {
        val data = AdvertiseData.Builder()
            .addManufacturerData(0xFFFF, chunk)
            .build()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        try {
            advertiser?.stopAdvertising(legacyAdvertiseCallback)
            advertiser?.startAdvertising(settings, data, null, legacyAdvertiseCallback)
        } catch (_: Exception) {
        }
    }

    private fun stopBroadcasting() {
        try {
            advertiser?.stopAdvertising(extendedAdvertiseCallback)
            advertiser?.stopAdvertising(legacyAdvertiseCallback)
        } catch (_: Exception) {
        }
        broadcasting = false
        chunks = emptyList()
        fullPayload = null
        broadcastButton.text = "Start Broadcasting"
        statusText.text = "Ready"
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.removeCallbacks(chunkRunnable)
    }

    private val extendedAdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            runOnUiThread {
                val modeLabel = if (useExtendedAdvertising) " (Extended)" else ""
                statusText.text = "Broadcasting...$modeLabel"
            }
        }

        override fun onStartFailure(errorCode: Int) {
            runOnUiThread {
                val msg = when (errorCode) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "Payload too large"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Extended advertising not supported"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                    else -> "Advertise failed: error $errorCode"
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val legacyAdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            runOnUiThread {
                statusText.text = "Broadcasting..."
            }
        }

        override fun onStartFailure(errorCode: Int) {
            runOnUiThread {
                val msg = when (errorCode) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "Payload too large"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "BLE advertising not supported"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                    else -> "Advertise failed: error $errorCode"
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!bluetoothMonitor.isBluetoothEnabled()) {
            showBanner("Bluetooth is off", "Turn On") { promptEnableBluetooth() }
            return
        }
        val needed = PermissionHelper.getMerchantPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            hideBanner()
        } else {
            val label = when (needed.first()) {
                Manifest.permission.ACCESS_FINE_LOCATION -> "Location"
                Manifest.permission.BLUETOOTH_ADVERTISE -> "Bluetooth advertising"
                Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth"
                else -> "Permissions"
            }
            showBanner("$label permission needed", "Grant") {
                permissionLauncher.launch(needed.toTypedArray())
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (broadcasting) {
            stopBroadcasting()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothMonitor.unregister()
        stopBroadcasting()
    }
}

class Ed25519Keypair(val publicKey: ByteArray, val privateKey: ByteArray) {
    companion object {
        fun generate(): Ed25519Keypair {
            val privateKey = ByteArray(32)
            java.security.SecureRandom().nextBytes(privateKey)
            val publicKey = Ed25519.derivePublicKey(privateKey)
            return Ed25519Keypair(publicKey, privateKey)
        }
    }
}
