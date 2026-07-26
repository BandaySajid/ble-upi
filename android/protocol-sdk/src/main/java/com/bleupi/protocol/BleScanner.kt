package com.bleupi.protocol

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BleScanner(
    private val bluetoothAdapter: BluetoothAdapter
) {
    companion object {
        val SERVICE_UUID = UUID.fromString("4F425031-0001-4000-8000-000000000000")
        const val MANUFACTURER_ID = 0xFFFF

        fun isSupported(): Boolean {
            return try {
                BluetoothAdapter.getDefaultAdapter() != null
            } catch (e: Exception) {
                false
            }
        }
    }

    interface Callback {
        fun onScanResult(scanResult: ScanResult)
        fun onScanFailed(errorCode: Int)
    }

    private var callback: Callback? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mfgData = result.scanRecord?.manufacturerSpecificData
            val hasData = mfgData != null && mfgData.size() > 0 && mfgData.get(MANUFACTURER_ID) != null
            android.util.Log.d("BleUpi", "onScanResult device=${result.device.address} rssi=${result.rssi} hasMfg=$hasData")
            if (hasData) {
                callback?.onScanResult(result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            android.util.Log.e("BleUpi", "scan failed: $errorCode")
            callback?.onScanFailed(errorCode)
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan(callback: Callback) {
        if (scanning) return

        this.callback = callback
        scanner = bluetoothAdapter.bluetoothLeScanner

        android.util.Log.d("BleUpi", "BleScanner.startScan adapter=${bluetoothAdapter != null} scanner=${scanner != null}")

        if (scanner == null) {
            callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0L)
            .build()

        try {
            // Use a manufacturer-data scan filter instead of null. Some device
            // BLE stacks (MediaTek, entry-level phones) silently drop all
            // scan results when no filter is provided. Filtering on our
            // 0xFFFF manufacturer ID matches only our beacons and works
            // reliably across all Android BLE implementations.
            // Empty mask = match manufacturer 0xFFFF with any data payload
            val filter = ScanFilter.Builder()
                .setManufacturerData(MANUFACTURER_ID, byteArrayOf(), byteArrayOf())
                .build()
            scanner?.startScan(listOf(filter), settings, scanCallback)
            scanning = true
            android.util.Log.d("BleUpi", "BleScanner starting scan with manufacturer filter")
        } catch (e: SecurityException) {
            android.util.Log.e("BleUpi", "SecurityException: ${e.message}", e)
            callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
        } catch (e: Exception) {
            android.util.Log.e("BleUpi", "startScan failed: ${e.message}", e)
            callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
        }
    }

    fun stopScan() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        scanning = false
        callback = null
    }

    fun isScanning(): Boolean = scanning
}
