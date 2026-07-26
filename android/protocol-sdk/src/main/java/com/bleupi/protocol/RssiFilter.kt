package com.bleupi.protocol

import java.util.LinkedList
import kotlin.math.abs

class RssiFilter(
    private val processNoiseQ: Float = 0.02f,
    private val measurementNoiseR: Float = 0.5f
) {
    enum class Proximity {
        NEAR,
        FAR,
        UNKNOWN
    }

    private var estimate = 0f
    private var errorCovariance = 1f
    private var initialized = false

    private val recentRssi = LinkedList<Float>()
    private val windowSize = 10
    private var lastAboveThresholdTime: Long = 0
    private var proximity = Proximity.UNKNOWN

    fun update(rssi: Int): Float {
        val rssiFloat = rssi.toFloat()
        var x = rssiFloat

        if (!initialized) {
            estimate = rssiFloat
            errorCovariance = 1f
            initialized = true
        } else {
            errorCovariance += processNoiseQ
            val kalmanGain = errorCovariance / (errorCovariance + measurementNoiseR)
            estimate += kalmanGain * (rssiFloat - estimate)
            errorCovariance = (1f - kalmanGain) * errorCovariance
        }

        recentRssi.add(estimate)
        if (recentRssi.size > windowSize) recentRssi.removeFirst()

        return estimate
    }

    fun classifyProximity(txPower: Byte, nearThresholdMeters: Float = 1.0f): Proximity {
        if (!initialized || recentRssi.isEmpty()) return Proximity.UNKNOWN

        val txPowerInt = txPower.toInt()
        // Without an in-band calibration the merchant transmits txPower=0
        // (sentinel for "unknown"). In that case we widen the NEAR band to 2.5m
        // so a customer standing at the counter still sees a payment card, while
        // beacons from across the room (>5m) remain correctly FAR. With a real
        // (negative) txPower we stick with the configured 1m threshold.
        val effectiveThreshold = if (txPowerInt >= 0) {
            maxOf(nearThresholdMeters, 2.5f)
        } else {
            nearThresholdMeters
        }

        val avgRssi = recentRssi.average().toFloat()
        val distance = estimateDistance(avgRssi, txPowerInt)
        val now = System.currentTimeMillis()

        val newProximity = if (distance <= effectiveThreshold) Proximity.NEAR else Proximity.FAR

        if (newProximity == Proximity.NEAR) {
            // NEAR is sticky — promote immediately, clear hysteresis timer.
            lastAboveThresholdTime = 0
            proximity = Proximity.NEAR
        } else {
            // FAR requires sustained-then-confirmed hysteresis (5s) to avoid bouncing.
            // But on the first observation we still expose FAR immediately, otherwise
            // the customer app sees UNKNOWN forever for room-distance beacons and
            // never fires onMerchantLost / never reacts.
            if (lastAboveThresholdTime == 0L) {
                lastAboveThresholdTime = now
            }
            if (now - lastAboveThresholdTime >= 5000) {
                proximity = Proximity.FAR
            } else if (proximity == Proximity.UNKNOWN) {
                // First observation of FAR — surface it right away so the customer
                // app's UI reflects reality. Subsequent FAR transitions still wait
                // 5s before "lost" fires, but the initial state is honest.
                proximity = Proximity.FAR
            }
        }

        return proximity
    }

    fun isLost(): Boolean = proximity == Proximity.FAR

    fun isNear(): Boolean = proximity == Proximity.NEAR

    fun currentEstimate(): Float = estimate

    private fun estimateDistance(rssi: Float, txPower: Int): Float {
        // txPower==0 is the merchant app's "no calibration" marker. Without an
        // in-band calibration byte, we can't compute path loss precisely, so we
        // fall back to a conservative assumed 1m reference (typical modern phones
        // sit between -55 and -65 dBm at 1m). With a real txPower value (always
        // negative in BLE) we use it directly. Path loss exponent 2.0 holds for
        // open-air LOS up to ~5m; we deliberately do NOT inflate the distance to
        // punish a missing calibration — that was the root cause of the customer
        // app declaring every phone in the room "FAR" and never showing a card.
        val txPowerAt1m = if (txPower >= 0) -60 else txPower
        val pathLossExponent = 2.0
        return Math.pow(10.0, (txPowerAt1m - rssi) / (10.0 * pathLossExponent)).toFloat()
    }
}
