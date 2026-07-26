package com.bleupi.customer

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

class BleUpiWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("BleUpi", "WorkManager: checking scanner health")
        val prefs = applicationContext.getSharedPreferences(
            BleUpiForegroundService.PREFS_NAME, Context.MODE_PRIVATE
        )
        val enabled = prefs.getBoolean(BleUpiForegroundService.KEY_NOTIFY_NEARBY, true)
        if (!enabled) return Result.success()

        try {
            val serviceIntent = android.content.Intent(applicationContext, BleUpiForegroundService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(serviceIntent)
            } else {
                applicationContext.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.w("BleUpi", "WorkManager restart failed: ${e.message}")
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "ble_upi_health_check"

        fun schedule(context: Context) {
            val prefs = context.getSharedPreferences(
                BleUpiForegroundService.PREFS_NAME, Context.MODE_PRIVATE
            )
            if (!prefs.getBoolean(BleUpiForegroundService.KEY_NOTIFY_NEARBY, true)) return

            val request = PeriodicWorkRequestBuilder<BleUpiWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
