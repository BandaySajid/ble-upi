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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bleupi.protocol.*
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var vpaInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var amountInput: EditText
    private lateinit var broadcastButton: Button
    private lateinit var dashboardButton: Button
    private lateinit var statusText: TextView
    private lateinit var elapsedText: TextView

    private var advertiser: BluetoothLeAdvertiser? = null
    private var broadcasting = false
    private var broadcastStartTime: Long = 0
    private var chunkIndex = 0
    private var chunks: List<ByteArray> = emptyList()
    private var merchantPublicKey: ByteArray? = null
    private var merchantPrivateKey: ByteArray? = null

    private val mainHandler = Handler(Looper.getMainLooper()!!)
    private val chunkRunnable = object : Runnable {
        override fun run() {
            if (!broadcasting || chunks.isEmpty()) return
            broadcastChunk(chunks[chunkIndex % chunks.size])
            chunkIndex++
            mainHandler.postDelayed(this, 100)
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
            startBroadcasting()
        } else {
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        vpaInput = findViewById(R.id.vpa_input)
        nameInput = findViewById(R.id.name_input)
        amountInput = findViewById(R.id.amount_input)
        broadcastButton = findViewById(R.id.broadcast_button)
        dashboardButton = findViewById(R.id.dashboard_button)
        statusText = findViewById(R.id.status_text)
        elapsedText = findViewById(R.id.elapsed_text)

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
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
            != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (needed.isEmpty()) {
            startBroadcasting()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
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

        android.util.Log.d("BleUpi", "Merchant startBroadcasting vpa=$vpa amount=$amount")

        chunks = PayloadEncoder.encodeMultiFrame(
            vpa = vpa,
            displayName = name,
            amountPaise = amount,
            merchantPublicKey = pk,
            merchantPrivateKey = sk,
            txPower = 0,
            caPrivateKey = caPrivateKey,
            maxChunkPayloadSize = 22
        )

        android.util.Log.d("BleUpi", "Merchant payload split into ${chunks.size} chunks")
        chunks.forEachIndexed { i, c -> android.util.Log.d("BleUpi", "  chunk[$i] = ${c.size} bytes: ${c.joinToString("") { "%02x".format(it) }}") }

        broadcastChunk(chunks[0])
        broadcasting = true
        broadcastStartTime = System.currentTimeMillis()
        broadcastButton.text = "Stop Broadcasting"
        statusText.text = "Broadcasting... (${chunks.size} frames)"
        chunkIndex = 1
        mainHandler.post(tickRunnable)
        mainHandler.postDelayed(chunkRunnable, 100)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun broadcastChunk(chunk: ByteArray) {
        val data = AdvertiseData.Builder()
            .addManufacturerData(0xFFFF, chunk)
            .build()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        try {
            advertiser?.stopAdvertising(advertiseCallback)
            advertiser?.startAdvertising(settings, data, null, advertiseCallback)
        } catch (_: Exception) {
        }
    }

    private fun stopBroadcasting() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {
        }
        broadcasting = false
        chunks = emptyList()
        broadcastButton.text = "Start Broadcasting"
        statusText.text = "Ready"
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.removeCallbacks(chunkRunnable)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            broadcasting = true
            runOnUiThread {
                broadcastButton.text = "Stop Broadcasting"
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

    override fun onPause() {
        super.onPause()
        if (broadcasting) {
            stopBroadcasting()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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
