package com.bleupi.protocol

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

class BluetoothStateMonitor(private val context: Context) {

    interface Callback {
        fun onBluetoothStateChanged(enabled: Boolean)
    }

    private var callback: Callback? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val state = intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
                ?: BluetoothAdapter.STATE_OFF
            when (state) {
                BluetoothAdapter.STATE_ON -> callback?.onBluetoothStateChanged(true)
                BluetoothAdapter.STATE_OFF -> callback?.onBluetoothStateChanged(false)
            }
        }
    }

    fun isBluetoothEnabled(): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.isEnabled == true
    }

    fun register(callback: Callback) {
        this.callback = callback
        context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    fun unregister() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        callback = null
    }
}
