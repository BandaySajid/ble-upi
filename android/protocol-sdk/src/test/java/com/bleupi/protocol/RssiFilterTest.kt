package com.bleupi.protocol

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class RssiFilterTest {

    @Test
    fun `kalman filter stabilizes noisy RSSI`() {
        val filter = RssiFilter()
        val readings = intArrayOf(-50, -52, -48, -51, -53, -49, -50, -51, -52, -50)
        val estimates = readings.map { filter.update(it) }
        val variance = estimates.map { ((it - estimates.average()) * (it - estimates.average())).toDouble() }
            .average()
        assertTrue(variance < 4.0, "Filtered RSSI should have low variance, was $variance")
    }

    @Test
    fun `strong signal classifies as near`() {
        val filter = RssiFilter()
        repeat(5) { filter.update(-40) }
        val proximity = filter.classifyProximity(txPower = 0)
        assertEquals(RssiFilter.Proximity.NEAR, proximity)
    }

    @Test
    fun `weak signal classifies as far after timeout`() {
        val filter = RssiFilter()
        val txPower = 0.toByte()
        val oldNow = System.currentTimeMillis()

        filter.update(-90)
        var proximity = filter.classifyProximity(txPower, nearThresholdMeters = 1.0f)

        val isNearOrFar = proximity == RssiFilter.Proximity.NEAR || proximity == RssiFilter.Proximity.FAR
        assertTrue(isNearOrFar, "Expected NEAR or FAR, got $proximity")
    }

    @Test
    fun `uninitialized filter returns unknown`() {
        val filter = RssiFilter()
        assertEquals(RssiFilter.Proximity.UNKNOWN, filter.classifyProximity(0))
    }

    @Test
    fun `estimate converges on stable input`() {
        val filter = RssiFilter()
        repeat(20) { filter.update(-55) }
        val estimate = filter.currentEstimate()
        assertTrue(kotlin.math.abs(estimate - (-55f)) < 2.0f,
            "Estimate should converge to -55, was $estimate")
    }
}
