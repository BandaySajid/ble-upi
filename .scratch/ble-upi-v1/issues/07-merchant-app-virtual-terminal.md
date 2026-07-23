# 07 — Merchant App — Virtual Terminal

**What to build:** A shopkeeper opens the Merchant App, enters a transaction amount, taps "Start Broadcasting", and their phone becomes the BLE beacon — every nearby customer phone with the Customer SDK picks up the payment request. Zero hardware cost merchant onboarding.

**Blocked by:** 03 — Protocol Core (payload codec + crypto)

**Status:** ready-for-agent

- [ ] Merchant App: UI with amount input (₹, numeric keypad or text field), merchant VPA + display name configuration (first-launch setup), "Start Broadcasting" / "Stop Broadcasting" toggle button
- [ ] Ed25519 keypair generated on first launch in Android KeyStore; public key and merchant identity persisted locally
- [ ] `BluetoothLeAdvertiser` broadcasts the binary Wire Protocol payload per RFC on the v1 Service UUID
- [ ] `TxPower` calibration byte populated from the device's BLE adapter reported TX power, not hardcoded
- [ ] Broadcast stops when user taps "Stop" or app moves to background (privacy: no persistent background advertising)
- [ ] Status display shows "Broadcasting..." with live elapsed time and configured amount
- [ ] Merchant App NEVER scans for or connects to customer devices — verify with Bluetooth permissions audit
- [ ] Instrumented test: start Virtual Terminal broadcast on a physical device, use Python emitter in reverse-scanner mode (or `nRF Connect`) to verify the broadcasted payload is well-formed and matches the entered amount + configured VPA
- [ ] Tests pass green with `./gradlew :merchant-app:connectedCheck`
