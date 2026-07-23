# 04 — BLE discovery pipeline (scanner + RSSI + cooldown)

**What to build:** The `:protocol-sdk` can discover nearby BLE broadcasts matching the protocol Service UUID, classify their distance from RSSI readings, and suppress duplicate payment requests that arrive during the cooldown window — all without the caller writing any BLE code.

**Blocked by:** 03 — Protocol Core (payload codec + crypto)

**Status:** ready-for-agent

- [ ] `BleScanner` starts/stops BLE scanning, filters results by protocol Service UUID, and delivers decoded `ScanResult` objects to its consumer
- [ ] `BleScanner` handles Android 12+ Bluetooth permissions, location services prompt, and Bluetooth-disabled gracefully
- [ ] `RssiFilter` applies a Kalman filter or moving average to raw RSSI series and classifies as "near" (< ~1m, counter-distance) vs "far" (> ~1m, not at counter)
- [ ] `RssiFilter` triggers "merchant lost" when RSSI stays below threshold for > 5 seconds
- [ ] `CooldownManager` suppresses the same (merchant short hash + nonce) pair within a 180s window
- [ ] `CooldownManager` passes through a different nonce from the same merchant immediately (next customer in queue)
- [ ] Unit tests for `RssiFilter`: known noisy RSSI series → stable near/far classification within acceptable tolerance
- [ ] Unit tests for `CooldownManager`: feed same nonce twice within window → suppressed; different nonce → allowed; after 180s window expiry → same nonce allowed again
- [ ] Unit tests for `BleScanner` using MockK to stub `BluetoothLeScanner` with known advertisement records — verify correct filtering and callback delivery
- [ ] Tests pass green with `./gradlew :protocol-sdk:test`
