package com.bleupi.protocol

interface BleUpiScanner {
    fun start(listener: BleUpiListener)
    fun stop()
    fun isScanning(): Boolean
}
