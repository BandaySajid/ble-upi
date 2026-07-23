# 08 — Merchant App — Companion Dashboard

**What to build:** A shopkeeper connects the Merchant App to their ESP32 hardware pod over BLE, provisions the merchant identity (VPA, display name, Ed25519 keypair), pushes dynamic amount updates, and monitors the pod's battery and signal health. The companion UI acts as the remote control for a dedicated hardware beacon.

**Blocked by:** 03 — Protocol Core (payload codec + crypto)

**Status:** ready-for-agent

- [ ] UI: scan for nearby ESP32 pods broadcasting the pod provisioning Service UUID (distinct from payment Service UUID)
- [ ] Connect to selected pod via BLE GATT, display connection state and pod identity
- [ ] Provisioning flow: write VPA, display name, and Ed25519 private key to the pod over GATT characteristic writes (ESP32 firmware out of scope — test against a Python mock GATT server)
- [ ] Dynamic amount update: enter amount on phone → write to pod's amount characteristic → pod re-broadcasts with new amount immediately
- [ ] Monitoring dashboard: live RSSI signal strength, pod battery level read via GATT characteristic
- [ ] OTA firmware update: select firmware binary, push to pod in chunks over GATT with progress bar
- [ ] Mode toggle: switch between "Virtual Terminal" (ticket 07) and "Companion Dashboard" modes from the app's main screen
- [ ] Tests: JUnit unit tests for GATT characteristic value encoding/decoding; MockK-stubbed BLE GATT interactions for provisioning write/read flows
- [ ] `python/pod_mock.py` — a Python bleak-based mock ESP32 GATT server that accepts provisioning writes and amount updates, for testing the Companion Dashboard without real hardware
