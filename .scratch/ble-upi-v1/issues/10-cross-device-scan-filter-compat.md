# Cross-Device BLE Scan Filter Compatibility

**Status:** completed

## Problem Statement

End-to-end testing between two Android phones — a Nothing Phone (MediaTek-based) and a Samsung Galaxy M17 — reveals a device-asymmetry bug. When the Nothing Phone runs the Merchant App (Virtual Terminal mode) and the Samsung runs the Customer App, the customer receives payment requests correctly. But when the roles are reversed, the Nothing Phone Customer App receives nothing — the scanner appears to start but never delivers any scan result callbacks, even though the Samsung merchant is visibly advertising on the BLE transport. A third device (Lava A015, also MediaTek-based) shows the same failure as the Nothing Phone.

## Solution

Replace the `null` scan-filter argument in `BleScanner.startScan()` with an explicit `ScanFilter` that matches the protocol's manufacturer ID (`0xFFFF`) with an empty mask. This ensures the Android BLE stack delivers scan results on all device BLE firmware implementations, including MediaTek and other entry-level chipsets that silently drop all results when no filter is provided.

## User Stories

1. As a merchant using a Samsung phone as a Virtual Terminal, I want my broadcast to be received by customers with any brand of Android phone, so that no customer is excluded based on their device.
2. As a customer using a MediaTek-based Android phone (Nothing, Lava, Xiaomi, etc.), I want my Customer App to detect nearby merchant beacons, so that I can pay at the counter without switching phones.
3. As a customer using any Android phone, I want the scanner to work immediately when I open the app, so I don't have to troubleshoot or toggle Bluetooth.
4. As a protocol SDK developer, I want `BleScanner.startScan()` to work identically across all Android BLE firmware implementations, so the SDK is a reliable drop-in library regardless of device vendor.
5. As a tester running end-to-end tests between two phones, I want bidirectional detection to work consistently, so I can validate the full payment flow in either direction.

## Implementation Decisions

- **`BleScanner.startScan()`** now passes a concrete `ScanFilter` to `bluetoothLeScanner.startScan()` instead of `null`. The filter uses `ScanFilter.Builder.setManufacturerData(MANUFACTURER_ID, byteArrayOf(), byteArrayOf())` — matching the `0xFFFF` manufacturer with an empty data mask, which matches any manufacturer-data payload under that ID.
- The scan settings (`SCAN_MODE_LOW_LATENCY`, `CALLBACK_TYPE_ALL_MATCHES`, `reportDelay=0`) remain unchanged.
- No other components are modified. The `BleScanner` interface (`Callback`, `startScan`, `stopScan`) is unchanged.
- The manufacturer ID constant `MANUFACTURER_ID = 0xFFFF` already exists in `BleScanner` companion object and is reused for the filter.
- Added diagnostic `Log.d` calls at the foreground service lifecycle points (`onCreate`, `onStartCommand`, `startScanning`) and merchant broadcast entry point to aid future debugging.

## Testing Decisions

- **Good test**: An end-to-end on-device test that verifies the Customer App receives a payment card when the Merchant App is broadcasting on the other device, in both device-role directions.
- **Unit test strategy**: Since `BleScanner` wraps the Android BLE stack (which cannot be unit-tested on the JVM), the unit-test seam is at the layer above — `DefaultBleUpiScanner` — which takes a `BluetoothAdapter` constructor parameter. Tests verify that `start()` calls through to `BleScanner.startScan()` and that scan results flow through the decoder/filter/cooldown pipeline correctly. A mock `BluetoothAdapter`/`BluetoothLeScanner` is not practical (final classes in Android SDK), so these seams are exercised in on-device integration tests.
- **Existing unit tests** for `RssiFilter`, `CborCodec`, `PayloadCodec`, `ChunkAssembly`, and `CooldownManager` continue to serve as the SDK's regression suite. The scan-filter fix does not introduce new unit-testable logic on its own — it changes an Android API invocation parameter.
- **Manual test checklist**: Bidirectional detection with Samsung ↔ Nothing Phone, Samsung ↔ Lava A015, and Nothing Phone ↔ Lava A015.

## Out of Scope

- Background-scan permission flow for Android 12+
- BLE 5 Extended Advertising or periodic advertising
- iOS / Swift SDK
- ESP32 hardware pod compatibility
- GATT escalation mode
- Persistent merchant key storage

## Further Notes

- The previous `startScan(null, settings, callback)` call works on Samsung's and Google's BLE firmware but fails silently on MediaTek-based devices (Lava A015, Nothing Phone). MediaTek's BLE stack appears to require at least one active `ScanFilter` to deliver `ScanResult` callbacks when scanning in background/foreground contexts — passing `null` is treated as a no-op rather than a wildcard.
- An alternative fix using `ScanFilter.Builder().setServiceUuid(SERVICE_UUID)` was considered but rejected because the merchant app advertises via manufacturer-specific data, not a service UUID. Matching on manufacturer data is the correct filter for our protocol.
