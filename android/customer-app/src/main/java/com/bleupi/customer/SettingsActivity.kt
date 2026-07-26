package com.bleupi.customer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.CompoundButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val notifySwitch = findViewById<SwitchCompat>(R.id.notify_switch)
        val batteryOptimizationRow = findViewById<TextView>(R.id.battery_optimization_row)

        val prefs = getSharedPreferences(BleUpiForegroundService.PREFS_NAME, MODE_PRIVATE)
        notifySwitch.isChecked = prefs.getBoolean(BleUpiForegroundService.KEY_NOTIFY_NEARBY, true)

        notifySwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(BleUpiForegroundService.KEY_NOTIFY_NEARBY, checked).apply()
            if (checked) {
                BleUpiWorker.schedule(this)
            } else {
                BleUpiWorker.cancel(this)
            }
        }

        batteryOptimizationRow.setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
