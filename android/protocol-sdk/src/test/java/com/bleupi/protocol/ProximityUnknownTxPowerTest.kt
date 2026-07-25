package com.bleupi.protocol

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Regression tests for the merchant-app-txPower=0 (uncalibrated) scenario.
 *
 * The merchant app advertises chunks with txPower=0 (no in-band calibration),
 * so the SDK falls back to -59 dBm at 1m when classifying proximity.
 * A real customer standing at the counter typically sees RSSI in -60..-70 dBm
 * with this signal. Bug: with the previous distance formula and 1m threshold
 * the scanner always classified those beacons as FAR, so the customer saw
 * nothing and (later) the foreground service got force-stopped by the system.
 */
class ProximityUnknownTxPowerTest {

    @Test
    fun `strong RSSI with txPower zero classifies as near`() {
        val filter = RssiFilter()
        repeat(5) { filter.update(-55) }
        assertEquals(RssiFilter.Proximity.NEAR, filter.classifyProximity(txPower = 0))
    }

    @Test
    fun `typical counter-distance RSSI with txPower zero classifies as near`() {
        val filter = RssiFilter()
        val readings = intArrayOf(-58, -60, -62, -61, -59, -60, -58, -61, -60, -59)
        readings.forEach { filter.update(it) }
        val proximity = filter.classifyProximity(txPower = 0)
        assertTrue(
            proximity == RssiFilter.Proximity.NEAR,
            "Expected NEAR for counter-distance beacon with no calibration, got $proximity"
        )
    }

    @Test
    fun `room-distance RSSI with txPower zero classifies as far`() {
        val filter = RssiFilter()
        val readings = intArrayOf(-88, -90, -89, -91, -92, -90, -89, -88, -90, -91)
        readings.forEach { filter.update(it) }
        val proximity = filter.classifyProximity(txPower = 0)
        assertTrue(
            proximity == RssiFilter.Proximity.FAR,
            "Expected FAR for room-distance beacon with no calibration, got $proximity"
        )
    }
}
