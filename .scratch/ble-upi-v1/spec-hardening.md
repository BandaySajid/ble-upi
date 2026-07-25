# BLE UPI v1 — Hardware Compatibility Hardening & Chunked Broadcasting

**Status:** ready-for-agent

## Problem Statement

The BLE UPI v1 reference implementation as originally specified targets BLE 5 Extended Advertising for full single-frame payload delivery. During end-to-end testing on real Android devices, this assumption broke:

- **Many low- and mid-range Android devices do not implement BLE 5 Extended Advertising.** They advertise only legacy ADV_IND, which is capped at 31 bytes per advertisement. A full v1 payload (~200 bytes after signature + CBOR certificate) cannot fit in one frame.
- **Extended-advertising devices rotate the broadcast MAC address every few seconds.** The scanner cannot use device MAC as the chunk-reassembly key — chunks from the same logical beacon arrive with different MACs, and never reassemble.
- **Android 10+ silently disables BLE scanning when Location Services are turned off.** Apps request `ACCESS_FINE_LOCATION` at runtime but cannot force-enable Location at the OS level; users with location off see a "Scanning..." UI forever.
- **Manufacturer-specific data (manufacturer ID + data) was overlooked in the original scan filter.** The original spec was service-UUID-based, but the legacy 31-byte advertisement cannot hold a 16-byte UUID plus any payload — the spec must pivot to manufacturer data with no service UUID.

Without addressing these, the v1 reference works only on recent flagship devices and fails silently on the long tail of phones that kirana shopkeepers and their customers actually use. A working proof of two phones pinging each other is the litmus test for v1 shippability.

## Solution

A focused hardening pass that does not change the public protocol surface (Service UUID, payload layout, certificate format, Ed25519 signing, etc.) but rewrites the **advertising and scanning transport** to work universally on Android BLE chipsets in production today:

- **Mandatory multi-frame chunking** at the merchant side — every broadcast splits the payload into 23-byte chunks (the largest payload size that fits legacy 31-byte ADV_IND once you subtract mandatory flags, the manufacturer AD header, and the chunk index header).
- **Chunked mode is the default, not an opt-in.** The single-frame mode from the original spec remains in the Wire Protocol RFC for future use on hardware that supports it, but the reference Android broadcaster never produces single-frame payloads.
- **Dedupe by merchant short hash, not device MAC.** Chunks carry the 4-byte merchant short hash (already part of the protocol header at bytes 2–5), so the customer reassembly buffer is keyed by `(merchant_short_hash, nonce_window)` rather than `(device_address)`. This survives BLE 4.x/5.x address rotation.
- **Manufacturer-data advertising and scanning.** Both advertiser and scanner use `AdvertiseData.addManufacturerData(0xFFFF, chunk)` and `ScanRecord.getManufacturerSpecificData(0xFFFF)`. No service UUID in the advertisement — it's all payload. Service UUID still defined in the RFC for non-Android broadcasters that prefer it.
- **Location-Services-aware Customer App UX.** When the user denies or has not enabled Location Services, the app surfaces a clear explanation screen (one tap to open Location settings) instead of staying in a silent "Scanning..." state. The Customer App does not block scanning indefinitely on the absence of a beacon — it tells the user why nothing is being detected.
- **Per-mode broadcast with explicit Legacy mode flag** where the API exposes it, to keep the broadcaster from accidentally being upgraded to Extended Advertising by future Android versions.

The Merchant App, Customer App, Python emitter, and Wire Protocol RFC all receive minor updates to align with these transport decisions. Public APIs (SDK listener interface, payload encoder/decoder, certificate format) stay unchanged.

## User Stories

1. As a shopkeeper on a low-end Android phone (≤2019 model, no BLE 5), I want the Merchant App to broadcast my payment beacon anyway, so that customers on any phone can pay me.
2. As a customer on a Samsung Galaxy A-series or similar mid-range phone, I want the Customer App to receive the merchant's beacon from a low-end broadcaster, so that the payment flow works even when the merchant can't afford flagship hardware.
3. As a customer using an old phone with a rotating BLE MAC (BLE 5 Extended Advertising feature), I want the Customer App to reassemble the merchant's full payment request across MAC changes, so that I don't get dropped payments when the merchant's MAC rotates.
4. As a customer who turned off Location Services for privacy, I want the Customer App to tell me clearly why nothing is being received, so that I can decide whether to enable Location briefly to pay.
5. As a developer testing the Customer SDK, I want the test emulator to deliver the same chunked payloads the merchant broadcasts, so that my SDK tests exercise the real transport.
6. As a firmware developer building a non-Android merchant pod, I want the Wire Protocol RFC to specify legacy-ADV chunking behavior, so that my ESP32 firmware can broadcast the same beacon format.
7. As a QA engineer, I want the ble-upi reference to demonstrate a successful Customer ↔ Merchant handoff on two real phones of different models, so that I can sign off on the v1 milestone.
8. As a customer on a phone whose manufacturer provides broken BLE 5 Extended Advertising driver, I want the system to fall back to legacy broadcast automatically, so that the feature works regardless of vendor quirks.
9. As a merchant who keeps the app open for hours at the counter, I want my broadcast to remain stable when the OS tries to promote me to Extended Advertising mid-session, so that customers don't lose my payment request halfway through.
10. As a customer who just denied Location permission, I want a one-tap path to enable it from the Customer App, so that the fix takes seconds not minutes.
11. As a developer integrating the Customer SDK into a third-party payment app, I want the chunked payload format to be an internal detail, so that I don't have to handle reassembly in my own app.
12. As a customer standing at a busy counter with many merchants around, I want the Customer App to ignore the broadcast after I've seen it once (cooldown), so that I don't get flooded with notifications as multiple merchants rotate their MACs.
13. As a shopkeeper with a flaky phone that drops some broadcast chunks, I want the merchant to keep rotating chunks fast enough that the customer gets all of them within one rotation, so that occasional dropouts don't block payments.
14. As a customer whose phone supports both legacy and extended scan, I want my Customer App to use legacy scan mode for compatibility, so that I can receive from any merchant.
15. As a developer writing tests for the chunk reassembler, I want a single deterministic unit under test, so that I don't have to mock BLE at all.

## Implementation Decisions

### Transport

- **Default chunk size: 23 bytes.** Legacy BLE 4.2 advertisement budget is 31 bytes. Subtracting the mandatory AD flags (3 bytes), the manufacturer-data AD header (4 bytes: 1 type + 2 length + 2 manufacturer ID), and the chunk index byte leaves 23 bytes of payload. The last chunk is shorter (e.g., 21 bytes) when the total payload isn't a multiple of 23.
- **Chunk header is one byte.** Bits 7–4 = chunk index (0–15), bits 3–0 = total chunk count (1–15). 16 chunks × 23 bytes = 368-byte payload ceiling — comfortable for typical v1 beacons (~200 bytes) and large enough for itemized-receipt expansion later.
- **Manufacturer ID = `0xFFFF`.** Reserved for proprietary use, no real-world vendor conflict. Allows the customer scanner to filter on `ScanRecord.getManufacturerSpecificData(0xFFFF)` without ambiguity.
- **No Service UUID in the legacy advertisement.** The protocol Service UUID remains in the RFC for non-Android broadcasters and for tests that want to verify the UUID was advertised, but the Android reference implementation does not advertise it. Customer scanners do not filter on it.
- **Merchants advertise chunks on a fixed cadence** (200 ms per chunk). Total cycle time scales with chunk count. For 10 chunks that's 2 seconds per full payload delivery — fast enough for a customer walking up to a counter.

### Chunk Reassembly

- **Reassembly key is `(merchant_short_hash, nonce_window)`, not device MAC.** Short hash lives in the protocol header at bytes 2–5 of chunk 0; the scanner extracts it before adding to the buffer. Same short hash from different MACs concatenates into the same logical beacon.
- **Chunk 0 always restarts the buffer.** When chunk index 0 arrives, any existing buffer for that short hash is reset. This handles the case where chunk 9 is missed but cycle 2 starts on time — buffer is fresh, no stale chunks.
- **Buffer timeout: 5 seconds from last chunk.** A buffer that receives chunks 0..6 but stalls is cleared on the 5-second mark so a subsequent cycle doesn't get poisoned.
- **Complete buffer → assemble → decode → listener callbacks.** Standard happy path; no special handling needed in user code.

### Customer App UX

- **Location Services state is checked on app start.** If `Settings.Secure.LOCATION_MODE` is off, the Customer App shows a "Location services required" screen with a button that opens `Settings.ACTION_LOCATION_SOURCE_SETTINGS`. No scanning is started until the user enables Location or dismisses the screen.
- **Merchant detected even with low RSSI.** The `RssiFilter` accepts any RSSI < −30 dBm as a potential detection in v1 (the Kalman filter + proximity classification was too aggressive at −50 dBm in testing). This will be tightened in v1.1 once we have real-world RSSI data.
- **Foreground service notification updates dynamically** when a `PaymentRequest` arrives — the notification becomes the payment card title + amount, with high priority. Tapping the notification returns to the app.

### Merchant App

- **Ed25519 keypair generated on first launch, stored in process memory only.** Persisting to disk is deferred — the same merchant can re-pair on a different pod if needed. Production should use Android KeyStore (Keystore-protected Ed25519 — requires Android 13+ APIs that aren't yet wired in the reference implementation).
- **Broadcast is always chunked, even if payload would fit single-frame.** Future BLE 5 single-frame support is opt-in via a developer flag, not the default.
- **Stop button and `onPause` immediately halt broadcasting.** Privacy: no background broadcasting after the user leaves the app or explicitly stops.
- **Service UUID field removed from merchant manifest.** Manifest advertises manufacturer-data permission, not service-UUID-based permission. (Manifest-level permissions stay the same.)

### Python Emitter

- **`emit_test.py` uses bleak's advertising API**, manufacturer data path (`add_manufacturer_data(0xFFFF, chunk)`). Chunking logic mirrors the Kotlin `PayloadEncoder.encodeMultiFrame()`.
- **CA keypair derivation matches the new `DevRootCa` on Android.** Both derive the Ed25519 seed from the same string. Otherwise cert verification fails. (The old hex literal was a typo / wrong length.)
- **Pod mock (`pod_mock.py`) GATT service definition stays as-is.** The pod mock is for testing the Companion Dashboard, not the customer-facing scanner, so it is unaffected by chunked-broadcast changes.

### Wire Protocol RFC

- **Multi-frame chunking section extended** with legacy 31-byte advertisement math and manufacturer-data packaging. Single-frame mode retained as the "BLE 5 Extended Advertising" future path.
- **Chunk header format documented** as a 1-byte field (4-bit index + 4-bit total). Previously implied, now explicit.
- **Manufacturer data path documented** as the canonical Android reference path. Service UUID section demoted to "alternative" status.

### What's explicitly NOT changing

- `BleUpiScanner` / `BleUpiListener` API surface
- `PaymentRequest`, `MerchantProfile`, `PayloadHeader`, `BleUpiError` data classes
- `PayloadEncoder` and `PayloadDecoder` (single-frame `encode` is still useful for testing)
- CBOR certificate format and CA signature scheme
- Ed25519 domain separator (`OBP1`) and signing input range
- Compression dictionary v1
- Time-based nonce format
- 180-second cooldown window

## Testing Decisions

- **What makes a good test:** test reassembly logic at the chunk level (pure data in, pure data out), not against live BLE. Test the chunked encoder produces exactly the right number of chunks for known payload sizes. Test that chunks reassemble into the original payload regardless of arrival order within a single cycle.
- **Modules tested (unit):**
  1. **Chunk reassembler** — fed chunk 0..9 in order → completes with full payload. Fed chunks out of order (5, 6, 4, 0, 1, 2, 3, 7, 8, 9) → completes. Fed chunk 0, then chunk 0 again before any others → resets cleanly. Fed chunk 0..6 only, then chunk 0 of next cycle → reset, new cycle starts. Short hash from chunk 0 used as key — chunks with different short hashes go to different buffers.
  2. **Chunked encoder** — full v1 payload (~200 bytes) → exactly 10 chunks of 23 bytes each (last chunk may be 21 bytes for the typical signing-input length). Each chunk's first byte decodes to its index in the high nibble and the total chunk count in the low nibble.
  3. **Manufacturer data round-trip** — encoder produces chunks whose manufacturer-data payload, prefixed with the chunk index byte, parses back to the original payload.
  4. **Location Services gating** — when Location is off, scanner callbacks should not be expected (test the Customer App's check, not the BLE layer).
- **Modules NOT unit-tested:** `BluetoothLeScanner` wrapping (would require MockK + Android stubs and was already covered by prior art in `BleScanner.kt`).
- **Prior art:** existing unit tests under `protocol-sdk/src/test/`. New tests live alongside.
- **End-to-end manual test:** two physical Android phones of different models (e.g., one low-end A-series, one mid-range Samsung). Open Merchant App on one, Customer App on the other. Customer should see the payment card within 3 seconds of the merchant starting broadcast. This is a release-gate manual test, not automated.

## Out of Scope

- **Single-frame BLE 5 Extended Advertising transport.** The protocol RFC supports it; the reference Android broadcaster does not produce it. Future work if a target device requires it.
- **Persistent key storage for merchant keypair** in Android KeyStore. Current implementation regenerates per session. Production deployment needs this; not in this hardening pass.
- **Compact merchant certificate with CA private key in production.** Still uses the dev `DevRootCa` derivation. Real CA public key swap is a deployment-time configuration change.
- **iOS Customer SDK.** Unchanged from the v1 spec.
- **ESP32 firmware.** Reference Python emitter updates only; firmware is a separate spec.
- **GATT escalation (mode `0x02`).** Not used in chunked mode 0x01 broadcasting; out of scope until itemized receipts demand it.
- **Adaptive RSSI thresholds.** Current 1-meter "near" cutoff is permissive. Field data needed before tightening.
- **Reconnect logic for the pod mock** — Pod mock is a development tool, not a long-running service.
- **Encryption of the chunked broadcast** — chunks are signed but not encrypted; payload contents (VPA, display name, amount) are visible to anyone scanning. This is the same security model as the original v1 spec and is intentional for offline verification. Encryption would require key exchange and is a v2 topic.

## Further Notes

- **Debug logging** stays in the SDK for this hardening pass (`android.util.Log.d("BleUpi", …)` on scan results and chunk headers). Strip with a build flag in v1.1.
- **DevRootCa private key** was previously hardcoded as a 31-byte hex string. The fix derives both private key and public key deterministically from the SHA-256 of `"BLE-UPI-DEV-ROOT-CA-v1"`. Both Python emitter and Android SDK use the same derivation. Production replaces with real CA-issued keys.
- **The original v1 spec (`.scratch/ble-upi-v1/spec.md`)** remains the source of truth for the higher-level protocol. This spec documents the transport-layer hardening produced by real-device testing. Any future spec that touches transport (BLE 5 single-frame, GATT escalation) should cite both documents.
- **Tickets to open** when implementing this spec:
  - 09: Chunked encoder/decoder tests + chunked-mode `encodeMultiFrame` already exists; needs unit tests
  - 10: `ChunkAssembly` buffer with short-hash keying + chunk-0-resets-cycle semantics
  - 11: `BleScanner` manufacturer-data path + remove service-UUID filter
  - 12: Customer App Location-Services gate + deep-link to settings
  - 13: `PayloadEncoder.encodeMultiFrame` parameter docs (max chunk payload sizes; legacy 23-byte default)
  - 14: Wire Protocol RFC additions (manufacturer data path, chunk-header byte format, legacy ADV_IND math)
  - 15: Python emitter `emit_test.py` manufacturer-data path + `DevRootCa` matching derivation
  - 16: End-to-end manual test plan for two-phone merchant-customer flow
