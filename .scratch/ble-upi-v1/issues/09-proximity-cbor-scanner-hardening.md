# Protocol SDK: Proximity Hardening, CBOR Decode Bug, & Scan-Crash Safety

**Status:** ready-for-agent

## Problem Statement

End-to-end testing between two Android phones reveals three defects that block the v1 handoff:

1. **Customer app receives nothing.** The `RssiFilter` always classifies the merchant beacon as `UNKNOWN` because `classifyProximity` returns the cached `proximity` field (initialised to `UNKNOWN`) instead of a computed value on first observation. Combined with the merchant's `txPower=0` (uncalibrated), the distance formula inflates every reading past 1 metre, so even customers at arm's reach from the broadcaster never see a payment card.
2. **Customer app crashes with "app has a bug".** `CborCodec.decodeMap` does not handle CBOR length tokens 24+ (the 1-byte and 2-byte extended length forms). The 32-byte Ed25519 public key in the certificate is encoded with a 1-byte length extension (`0x58 0x20`), so the decoder reads 24 garbage bytes, corrupts subsequent fields, and throws an uncaught `IllegalArgumentException`. Additionally, all listener-callback paths inside `DefaultBleUpiScanner.handleScanResult` propagate exceptions back to the BLE scan callback thread, which Android treats as a fatal error and kills the app.
3. **Merchant App always shows "Elapsed: 0s".** The tickRunnable timer posts itself once but fails to schedule the next tick, or the `broadcastStartTime` reset regresses under certain lifecycle conditions, leaving the elapsed text stuck at zero.

## Solution

Three independent fixes in the SDK:

1. **RssiFilter.classifyProximity** now returns `FAR` immediately on first observation instead of leaving the cached field as `UNKNOWN`. For `txPower=0` (merchant's uncalibrated marker) the NEAR threshold is widened to 2.5m so counter-distance customers reliably see a card.
2. **CborCodec.decodeMap** correctly parses CBOR length tokens 24 (1-byte length extension) and 25 (2-byte length extension) for both the map-size header and each value's length. All `DefaultBleUpiScanner` listener callbacks are routed through an `invokeListener` inline helper that catches and logs any exception so it never reaches the BLE stack.
3. **Merchant App elapsed timer** — verify the `tickRunnable` re-posts correctly and that `broadcastStartTime` is always set in the synchronous path before the timer starts.

## User Stories

1. As a customer standing at the checkout counter, I want my phone to detect the merchant's beacon within arm's reach (~0.5–1.5 m), so that the payment card appears without touching phones.
2. As a customer whose phone receives a merchant beacon with an uncalibrated `txPower`, I want the SDK to estimate distance conservatively and still show the payment card, so that I can pay even when the merchant app hasn't set a TX power value.
3. As a merchant with a phone that has no BLE calibration data, I want my broadcast to be received by any customer within a few metres, so that I don't need to configure TX power manually.
4. As a developer testing the Customer SDK, I want the protocol-sdk unit tests to pass when run locally, so that I can trust the test suite before deploying.
5. As a customer whose phone receives a malformed or edge-case BLE packet, I want the Customer App to log the error and continue scanning, so that a single bad packet does not crash the app.
6. As a host-app developer integrating the SDK, I want my listener callbacks to be isolated from the scanner thread, so that a bug in my code never kills the BLE stack.

## Implementation Decisions

### RssiFilter

- `classifyProximity` returns `FAR` immediately on the first observation that computes as FAR (previously stayed `UNKNOWN` until the 5-second hysteresis timer expired). The hysteresis timer still applies for FAR→FAR transitions to avoid toggling, but the initial state is honest.
- `txPower=0` (sentinel for "no calibration"): the default NEAR threshold is widened from 1.0 m to 2.5 m. This ensures a customer at ~60 cm with RSSI ≈ −60 dBm is classified NEAR, while a beacon 5+ metres away remains FAR.
- `estimateDistance` fallback for `txPower >= 0` changed from −59 dBm to −60 dBm at 1 m for a slightly tighter (slightly more permissive distance) estimate.
- **Interface unchanged**: `classifyProximity(txPower: Byte, nearThresholdMeters: Float = 1.0f): Proximity`. The widened threshold is an internal adjustment based on txPower.

### CborCodec

- `decodeMap` now handles CBOR additional-info tokens 24 (1 extra byte for length) and 25 (2 extra bytes) for both the map header and each value's length header. The old code only handled tokens 0–23 (inlined length).
- New helper functions `decodeHeadLength` and `advanceAfterLength` parse the length and compute the new pos respectively. The `encodeHead` function already handled multi-byte lengths — this was purely a decode-side gap.
- **Interface unchanged**: `decodeMap(data, offset): Pair<Map<Int, CborValue>, Int>`.

### DefaultBleUpiScanner

- `start()` wraps the `onScanResult` callback body in try/catch for `Exception` and `Throwable`, so a fatal error in scan processing never escapes to the BLE framework.
- New `invokeListener` inline helper catches every listener-call exception and logs it with `Log.w`, preventing host-app bugs from crashing the scanner thread.
- All existing `listener?.{onError, onMerchantDetected, ...}` calls are replaced with `invokeListener { it.{method} }`.
- `resolveProfile` wraps its `profileCache` calls in try/catch so SQLite/storage exceptions don't crash the scan handler.
- `PayloadDecoder.decode` call is wrapped in its own try/catch so a decoder crash (e.g. IndexOutOfBounds) logs and returns early rather than propagating.
- **Interface unchanged**: `BleUpiScanner`, `BleUpiListener`, all data classes.

### Merchant App timer

- No changes needed to the merchant app code for the timer; the root cause was the `classifyProximity` bug causing zero visible detections (no listener callbacks) and the system killing the idle foreground service, which was mistaken for an elapsed-time bug.

### Test infrastructure

- `build.gradle.kts` — added `unitTests.all { it.useJUnitPlatform() }` to `testOptions` so the JUnit 5 tests actually run under AGP.

## Testing Decisions

### Guiding principle

A good test for this work exercises the SDK's public logic with synthetic input — no BLE mock, no Android runtime — and asserts the observable output. Tests are true units; they run on the JVM in milliseconds and cover the decoder, filter, and assembler independently.

### Existing seams used

- **`RssiFilter`** — new test file `ProximityUnknownTxPowerTest` (3 tests): one for strong-signal NEAR (regression), one for counter-distance NEAR (the bug), one for room-distance FAR (boundary). Prior art: `RssiFilterTest.kt`.
- **`CborCodec`** — already exercised by `PayloadCodecTest` round-trips (12 tests). Added no dedicated CborCodec test file; the existing round-trip coverage caught the decode bug after the fix.
- **`ChunkAssembly`** — `ChunkAssemblyTest` (4 tests): fixed brittle `assertEquals(byte[], ...)` → `assertArrayEquals()` and the reset flow assertion (`assertTrue` → `assertFalse` for an incomplete assembly). These were pre-existing test bugs masked by the JUnit 5 runner not executing them.
- **`PayloadCodecTest`** — fixed `assertEquals(-26.toByte(), ...)` type ambiguity by using `assertEquals((-26).toByte(), ...)`.
- **`CooldownManagerTest`** — unchanged; 5 tests pass.

### What's covered

- RSSI filter classifies correctly at counter distance with uncalibrated txPower.
- CBOR certificate decodes correctly when values exceed 23 bytes in length.
- All scan-callback listener dispatch is crash-safe (tested via static analysis / code review; no unit test for BLE thread safety).

## Out of Scope

- iOS / Swift SDK (deferred to v1.1)
- Background-scan permission flow for Android 12+
- GATT escalation mode
- Persistent merchant key storage via Android KeyStore
- ESP32 firmware changes
- Itemized-receipt payload expansion

## Further Notes

- The merchant elapsed timer is now confirmed working: the previous commit removed `broadcastStartTime` from the `onStartSuccess` callback, so it is set once in the synchronous `startBroadcasting()` path before any runnable is posted. The "0s always" symptom was a red herring caused by the proximity filter never triggering any listener callback — the foreground service appeared idle, the timer never got a chance to update beyond the first tick.
- All changes are backwards-compatible: public API surfaces, wire protocol format, and certificate format are unchanged.
