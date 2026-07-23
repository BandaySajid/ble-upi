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

        val avgRssi = recentRssi.average().toFloat()
        val distance = estimateDistance(avgRssi, txPower.toInt())
        val now = System.currentTimeMillis()

        val newProximity = if (distance <= nearThresholdMeters) Proximity.NEAR else Proximity.FAR

        if (newProximity == Proximity.FAR) {
            if (lastAboveThresholdTime == 0L) {
                lastAboveThresholdTime = now
            }
            if (now - lastAboveThresholdTime >= 5000) {
                proximity = Proximity.FAR
            }
        } else {
            lastAboveThresholdTime = 0
            proximity = Proximity.NEAR
        }

        return proximity
    }

    fun isLost(): Boolean = proximity == Proximity.FAR

    fun currentEstimate(): Float = estimate

    private fun estimateDistance(rssi: Float, txPower: Int): Float {
        val ratioDb = txPower - rssi
        val ratioLinear = Math.pow(10.0, ratioDb / 10.0)
        return Math.sqrt(ratioLinear).toFloat()
    }
}
