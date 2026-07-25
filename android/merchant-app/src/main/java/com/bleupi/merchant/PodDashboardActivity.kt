package com.bleupi.merchant

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelUuid
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID

class PodDashboardActivity : AppCompatActivity() {

    companion object {
        val POD_SERVICE_UUID = UUID.fromString("4F425031-0002-4000-8000-000000000000")
        private val VPA_CHAR_UUID = UUID.fromString("4F425031-0003-4000-8000-000000000001")
        private val DISPLAY_NAME_CHAR_UUID = UUID.fromString("4F425031-0003-4000-8000-000000000002")
        private val PRIVATE_KEY_CHAR_UUID = UUID.fromString("4F425031-0003-4000-8000-000000000003")
        private val AMOUNT_CHAR_UUID = UUID.fromString("4F425031-0003-4000-8000-000000000004")
        private val BATTERY_CHAR_UUID = UUID.fromString("4F425031-0003-4000-8000-000000000005")
        private const val OTA_REQUEST_CODE = 1001
    }

    private lateinit var statusText: TextView
    private lateinit var podRssiText: TextView
    private lateinit var podBatteryText: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var vpaInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var amountInput: EditText
    private lateinit var provisionButton: Button
    private lateinit var setAmountButton: Button
    private lateinit var otaButton: Button
    private lateinit var scanButton: Button

    private var connectedGatt: BluetoothGatt? = null

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pod_dashboard)

        statusText = findViewById(R.id.pod_status)
        podRssiText = findViewById(R.id.pod_rssi)
        podBatteryText = findViewById(R.id.pod_battery)
        deviceList = findViewById(R.id.device_list)
        vpaInput = findViewById(R.id.pod_vpa_input)
        nameInput = findViewById(R.id.pod_name_input)
        amountInput = findViewById(R.id.pod_amount_input)
        provisionButton = findViewById(R.id.provision_button)
        setAmountButton = findViewById(R.id.set_amount_button)
        otaButton = findViewById(R.id.ota_button)
        scanButton = findViewById(R.id.scan_devices_button)

        scanButton.setOnClickListener { scanForPods() }
        provisionButton.setOnClickListener { provisionPod() }
        setAmountButton.setOnClickListener { setAmount() }
        otaButton.setOnClickListener { pickOtaFile() }
    }

    @SuppressLint("MissingPermission")
    private fun scanForPods() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return

        deviceList.removeAllViews()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(POD_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        var scanCb: ScanCallback? = null
        scanCb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                runOnUiThread {
                    val device = result.device
                    val button = Button(this@PodDashboardActivity).apply {
                        text = "${device.name ?: "Pod"} - ${device.address}"
                        setOnClickListener {
                            connectToPod(device)
                            scanCb?.let { scanner.stopScan(it) }
                        }
                    }
                    deviceList.addView(button)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                runOnUiThread {
                    statusText.text = "Scan failed: $errorCode"
                }
            }
        }

        scanner.startScan(listOf(filter), settings, scanCb)

        statusText.text = "Scanning for pods..."
    }

    @SuppressLint("MissingPermission")
    private fun connectToPod(device: BluetoothDevice) {
        statusText.text = "Connecting to ${device.name ?: device.address}..."

        connectedGatt?.close()
        connectedGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                runOnUiThread {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            statusText.text = "Connected to pod"
                            gatt.discoverServices()
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            statusText.text = "Disconnected"
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                statusText.text = "Pod services discovered"
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                runOnUiThread {
                    val label = when (characteristic.uuid) {
                        VPA_CHAR_UUID -> "VPA"
                        AMOUNT_CHAR_UUID -> "Amount"
                        else -> "Config"
                    }
                    statusText.text = "$label written: ${if (status == 0) "OK" else "failed"}"
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun provisionPod() {
        val gatt = connectedGatt ?: run {
            Toast.makeText(this, "Not connected to pod", Toast.LENGTH_SHORT).show()
            return
        }

        val vpa = vpaInput.text.toString().trim()
        val name = nameInput.text.toString().trim()

        if (vpa.isEmpty() || name.isEmpty()) {
            Toast.makeText(this, "Enter VPA and name", Toast.LENGTH_SHORT).show()
            return
        }

        writeCharacteristic(gatt, VPA_CHAR_UUID, vpa.toByteArray(Charsets.UTF_8))
        writeCharacteristic(gatt, DISPLAY_NAME_CHAR_UUID, name.toByteArray(Charsets.UTF_8))

        val privateKey = ByteArray(32)
        java.security.SecureRandom().nextBytes(privateKey)
        writeCharacteristic(gatt, PRIVATE_KEY_CHAR_UUID, privateKey)

        statusText.text = "Provisioning pod..."
    }

    @SuppressLint("MissingPermission")
    private fun setAmount() {
        val gatt = connectedGatt ?: run {
            Toast.makeText(this, "Not connected to pod", Toast.LENGTH_SHORT).show()
            return
        }
        val amountStr = amountInput.text.toString().trim()
        if (amountStr.isEmpty()) return
        val amountPaise = (amountStr.toDouble() * 100).toLong()

        val buf = ByteArray(4)
        buf[0] = ((amountPaise shr 24) and 0xFF).toByte()
        buf[1] = ((amountPaise shr 16) and 0xFF).toByte()
        buf[2] = ((amountPaise shr 8) and 0xFF).toByte()
        buf[3] = (amountPaise and 0xFF).toByte()

        writeCharacteristic(gatt, AMOUNT_CHAR_UUID, buf)
    }

    private fun pickOtaFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
        }
        startActivityForResult(intent, OTA_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OTA_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                statusText.text = "OTA file selected: ${uri.lastPathSegment}"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(gatt: BluetoothGatt, uuid: UUID, value: ByteArray) {
        val service = gatt.services.firstOrNull()
        val characteristic = service?.getCharacteristic(uuid) ?: return
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = value
        gatt.writeCharacteristic(characteristic)
    }

    override fun onDestroy() {
        connectedGatt?.close()
        super.onDestroy()
    }
}
