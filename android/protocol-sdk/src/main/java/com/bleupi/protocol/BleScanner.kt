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
            callback?.onScanResult(result)
        }

        override fun onScanFailed(errorCode: Int) {
            callback?.onScanFailed(errorCode)
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan(callback: Callback) {
        if (scanning) return

        this.callback = callback
        scanner = bluetoothAdapter.bluetoothLeScanner

        if (scanner == null) {
            callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
            scanning = true
        } catch (e: SecurityException) {
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
