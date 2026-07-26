package com.bleupi.protocol

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import java.util.UUID

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

        fun supportsExtendedAdvertising(): Boolean {
            return try {
                if (android.os.Build.VERSION.SDK_INT >= 35) {
                    val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
                    adapter.isLeExtendedAdvertisingSupported
                } else {
                    false
                }
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

    private val scanRestartHandler = Handler(Looper.getMainLooper()!!)
    private val scanRestartRunnable = object : Runnable {
        override fun run() {
            if (!scanning) return@run
            try {
                scanner?.stopScan(scanCallback)
                Thread.sleep(30)
                val settings = buildScanSettings()
                val filters = listOf(buildScanFilter())
                scanner?.startScan(filters, settings, scanCallback)
                android.util.Log.d("BleUpi", "BleScanner periodic restart")
            } catch (_: Exception) {
            }
            if (scanning) {
                scanRestartHandler.postDelayed(this, 15_000L)
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mfgData = result.scanRecord?.manufacturerSpecificData
            val hasData = mfgData != null && mfgData.size() > 0 && mfgData.get(MANUFACTURER_ID) != null
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

        try {
            val settings = buildScanSettings()
            val filters = listOf(buildScanFilter())
            scanner?.startScan(filters, settings, scanCallback)
            scanning = true
            android.util.Log.d("BleUpi", "BleScanner starting scan with manufacturer filter, extended=${supportsExtendedAdvertising()}")
            scanRestartHandler.postDelayed(scanRestartRunnable, 15_000L)
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
            scanRestartHandler.removeCallbacks(scanRestartRunnable)
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        scanning = false
        callback = null
    }

    fun isScanning(): Boolean = scanning

    private fun buildScanSettings(): ScanSettings {
        return ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0L)
            .build()
    }

    private fun buildScanFilter(): ScanFilter {
        return ScanFilter.Builder()
            .setManufacturerData(MANUFACTURER_ID, byteArrayOf(), byteArrayOf())
            .build()
    }
}
