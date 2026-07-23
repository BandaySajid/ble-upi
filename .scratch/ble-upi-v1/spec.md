Status: ready-for-agent

# BLE UPI v1 — Protocol SDK & Two-Application Architecture

## Problem Statement

Current UPI retail payments require customers to open their camera, scan a QR code, enter an amount, and authenticate — a multi-step process with friction at every stage. In high-volume environments (kirana stores, food stalls, checkout counters), this creates queues. The existing v0 prototype proves BLE proximity payments work but uses an inverted topology (phone advertises, terminal connects) that is privacy-invasive, battery-expensive, and can't scale past ~7 concurrent GATT connections. No reusable SDK exists for payment app developers to integrate this flow.

## Solution

A two-tier contactless payment system:

- **Tier 1 (Wire Protocol)**: A language-agnostic binary specification defining how merchant payment beacons are packed, compressed, signed, and broadcast over BLE.
- **Tier 2 (High-Level SDK)**: A Kotlin library that payment apps (PhonePe, GPay, Paytm) import. Handles BLE scanning, certificate verification, RSSI proximity filtering, payload decoding, cooldown management, and UPI intent hand-off. A Swift/iOS SDK is deferred.
- **Two Android applications**: A **Merchant App** (dual-mode: companion dashboard for ESP32 provisioning, or Virtual Terminal phone-as-beacon) and a **Customer App** (passive scanner with integrated Customer SDK).

The system is broadcast-first: merchant beacons are received simultaneously by unlimited customer phones. GATT connections are an absolute last resort for oversized payloads.

## User Stories

1. As a customer at a checkout counter, I want my phone to automatically detect the merchant's payment request via BLE without opening my camera or scanning a QR code, so that checkout is instant.
2. As a customer, I want to see the merchant's name, VPA, and transaction amount on an on-screen payment card the moment I'm in proximity, so that I know who I'm paying and how much.
3. As a customer, I want to tap a single "Pay Now" button that launches my installed UPI app with the pre-filled payment details, so that I complete the transaction in two taps.
4. As a customer using an app that integrates the Customer SDK, I want payment detection to work even when the app is in the background, so that I don't need to keep the app open.
5. As a customer, I want to be confident that the payment request I receive is genuinely from the merchant standing in front of me and not a spoofed beacon, so that I don't pay a fraudster.
6. As a customer, I want to see a notification the moment a payment request arrives, even if my phone is locked, so that I don't miss payment prompts.
7. As a customer, I want to NOT see the same payment request repeatedly while standing at the counter (cooldown), so that I'm not spammed with notifications.
8. As a customer, I want my phone to NEVER broadcast BLE signals, so that stores and third parties cannot track my movements via BLE fingerprinting.
9. As a shopkeeper, I want to configure my merchant VPA and display name once and have my ESP32 pod continuously broadcast payment beacons, so that every customer at my counter receives my payment request automatically.
10. As a micro-merchant without hardware budget, I want to turn my Android phone into a virtual BLE beacon using the Merchant App, so that I can accept proximity payments with zero hardware cost.
11. As a shopkeeper, I want to dynamically change the transaction amount on my ESP32 pod from the merchant companion app, so that I can handle variable purchases.
12. As a payment app developer (PhonePe, GPay), I want to import a single Kotlin SDK module that handles all BLE scanning, crypto verification, and UPI hand-off, so that I integrate proximity payments without writing BLE code.
13. As a payment app developer, I want the Customer SDK to expose clean callbacks (`onMerchantDetected`, `onMerchantLost`, `onPaymentRequestReceived`) rather than raw byte arrays, so that I build a polished UI on top.
14. As a firmware developer building an ESP32 merchant pod, I want a Wire Protocol specification document that defines the exact binary layout, compression dictionary, and signing algorithm, so that my firmware is interoperable with all SDK consumers.
15. As a developer testing on a laptop, I want a Python-based reference merchant broadcaster/emitter that simulates a merchant beacon, so that I can test the Customer SDK without dedicated hardware.
16. As a customer visiting a shop for the first time, I want my phone to still verify the merchant's identity (via in-band certificate) even though I've never been there before, so that offline verification works everywhere.
17. As a customer, I want the app to distinguish between a merchant at arm's reach and a beacon 10 meters away in the same room, so that I'm only prompted to pay at the counter I'm standing at.
18. As a customer, I want expired payment requests (from a previous visit or a replay attacker) to be automatically rejected, so that I never see stale or fraudulent payment prompts.
19. As a customer, I want my phone to verify the merchant's cryptographic signature without needing an internet connection, so that payments work in underground metros, rural markets, and basements.
20. As a shopkeeper, I want the Merchant App to show me which customers have received my payment request and acknowledged it, so that I know the system is working.

## Implementation Decisions

### Architecture

- **Two Android Gradle modules** in `android/`: `merchant-app` and `customer-app`. A third library module `protocol-sdk` (the Customer SDK) is shared between them.
- The current prototype (`com.example.blechat` as GATT Server) is archived/deprecated. All new code follows ADR-0001 topology: merchant broadcasts, customer passively scans.
- The Wire Protocol specification exists as a standalone document, not code. The SDK encodes/decodes per that spec.

### Wire Protocol

- **Service UUID**: New UUID allocated for v1 (current `6e400001-...` is the v0 prototyping UUID). Registered as the protocol's well-known service UUID in the RFC.
- **Binary payload layout** (BLE 5.0 Extended Advertising, up to 255 bytes):

  ```
  Bytes 0-1:    Protocol Header & Flags (version, Payload Mode)
  Bytes 2-5:    Merchant Short Hash (32-bit truncated hash)
  Byte 6:       VPA Handle Dictionary Suffix (0x01-0x64, or 0x00 raw)
  Bytes 7-9:    Transaction Amount in Paisa (24-bit uint, max ₹167,772.15)
  Bytes 10-13:  Time-Based Nonce (UNIX ÷ 30s, 32-bit sliding window)
  Bytes 14-77:  Full Ed25519/secp256r1 Signature (64 bytes)
  Byte 78:      TX Power Calibration Byte
  Bytes 79+:    Compact Merchant Certificate (variable, in-band or multi-frame)
  ```

- **Payload Mode flags** (bits 0-1 of byte 0):
  - `0x00` — Single-frame broadcast (compressed, fits in one frame)
  - `0x01` — Multi-frame chunked broadcast (reassembled in memory)
  - `0x02` — GATT escalation required (customer connects as GATT Client)

- **Compression dictionary**: Static lookup table of ~128 Indian bank handles shipped in Wire Protocol RFC. Identical in all SDK builds. `0x00` = raw suffix, fall through to multi-frame chunking.
- **Signing input**: Domain separator `OBP1` + bytes 0-13 (header through nonce). Merchant private key signs; customer verifies with merchant public key extracted from certificate.
- **Nonce expiry**: Packets older than 180 seconds (6 windows of 30s) are rejected. Replay protection is local and offline.

### Customer SDK (Kotlin Library Module)

- **Public API surface**:
  ```kotlin
  interface BleUpiScanner {
      fun start(listener: BleUpiListener)
      fun stop()
  }

  interface BleUpiListener {
      fun onMerchantDetected(merchant: MerchantProfile)
      fun onMerchantLost(merchantId: String)
      fun onPaymentRequestReceived(request: PaymentRequest)
      fun onError(error: BleUpiError)
  }

  data class PaymentRequest(
      val merchantId: String,
      val merchantName: String,
      val vpa: String,
      val amountPaise: Long,
      val upiUri: String
  )

  data class MerchantProfile(
      val shortHash: Int,
      val displayName: String,
      val vpa: String,
      val categoryIcon: Int?,
      val publicKey: ByteArray
  )
  ```
- Internal modules: `BleScanner` (scan + filter by Service UUID), `PayloadDecoder` (binary → PaymentRequest), `CryptoVerifier` (cert chain + payload signature), `RssiFilter` (Kalman/moving average → distance classify), `CooldownManager` (suppress duplicate nonces within window), `UpiIntentBuilder` (construct `upi://pay?...` URI).
- Root CA Public Key hardcoded as a compile-time constant in the SDK.
- Merchant profile cache: SQLite database in app-private storage, populated on first visit and synced opportunistically.
- Cooldown: 180s window — same nonce ignored after first display. Distinct nonces from same merchant (next customer in queue) pass through immediately.

### Merchant App

- **Mode: Virtual Terminal** — Uses `BluetoothLeAdvertiser` to broadcast the binary payload on BLE 5.0 Extended Advertising. UI: enter amount, tap "Start Broadcasting", app becomes the beacon. No customer connection handling — pure broadcast.
- **Mode: Companion Dashboard** — Connects to ESP32 pod over local BLE GATT for provisioning (writes VPA, private key, display name to NVS), amount updates (quick GATT write), OTA firmware push, battery/signal monitoring. The pod, not the phone, does the broadcasting.
- Merchant's Ed25519 key pair generated during first setup in Android KeyStore (Virtual Terminal) or ESP32 secure storage (Hardware Pod).

### Customer App

- Thin UI shell over the Customer SDK. Single activity with permission flow, status pill, payment card view, and a "Pay Now" button. Does NOT advertise. Does NOT run a GATT server.
- Background scanning via foreground service with persistent notification.
- On receiving a valid PaymentRequest: displays card with merchant name, VPA, amount. Stop button dismisses. Pay Now button constructs UPI intent and fires `ACTION_VIEW`.
- On merchant lost (RSSI drops below threshold for > 5s): hide the payment card, log "Merchant out of range".

### Python Reference Emitter

- `python/emit_test.py` — a desktop test utility that broadcasts the binary Wire Protocol payload using `bleak`. Used by SDK developers to test without an ESP32 or second Android phone. Replaces `upi_server.py` and `scan_test.py` which implemented the v0 inverted topology.

### Android Build & Dependencies

- `compileSdk 34`, `minSdk 26`, `targetSdk 34`, Kotlin 1.9+
- SDK module has zero third-party dependencies (uses `javax.crypto` for Ed25519 via BouncyCastle or platform `KeyStore`)
- App modules depend on `:protocol-sdk`
- Gradle multi-module setup: `settings.gradle.kts` includes `:merchant-app`, `:customer-app`, `:protocol-sdk`

## Testing Decisions

- **What makes a good test**: Only test external behavior at module boundaries. Internal BLE stack interactions are stubbed/mocked. Crypto code is tested with known-answer test vectors (RFC test vectors).
- **Modules tested**:
  1. `PayloadDecoder` — round-trip encode/decode with hex fixtures covering all Payload Modes, edge cases (max amount, raw VPA suffix, dictionary fallback)
  2. `CryptoVerifier` — verify with known keypair + known payload → assert pass; tampered payload → assert fail; expired nonce → assert fail; cross-protocol replay (wrong domain separator) → assert fail
  3. `RssiFilter` — known noisy RSSI series → assert stable distance classification within tolerance
  4. `CooldownManager` — feed same nonce twice within window → assert suppressed; different nonce → assert allowed; after window expiry → assert allowed
  5. Customer App integration — instrumented test on physical device: Merchant App broadcasts known payload → Customer App receives and renders payment card → verify UI elements populated correctly
- **Prior art**: No existing tests in the codebase. This establishes the testing pattern.
- **Test framework**: JUnit 5 + MockK for unit tests. AndroidX Test + Espresso for instrumented tests.

## Out of Scope

- **iOS Customer SDK**: Swift implementation deferred to a future spec.
- **ESP32 firmware**: The C/C++ firmware for hardware merchant pods is a separate spec. The Merchant App's companion mode provides the BLE interface to it, but the pod firmware itself is out of scope.
- **Payment Authority backend**: The central server that signs merchant certificates, maintains the compression dictionary, and publishes the Root CA key is out of scope. The SDK hardcodes a test Root CA key for development.
- **UPI Lite X (offline wallet)**: The on-device balance deduction and batched settlement are out of scope. The Customer SDK constructs standard `upi://pay?...` URIs for existing UPI app hand-off.
- **Itemized receipts / GATT escalation payloads**: The GATT fallback path (mode `0x02`) is specified architecturally but its implementation is deferred. v1 only handles modes `0x00` and `0x01`.
- **Merchant profile background sync**: The local cache is populated from in-band certificates only. Background sync from a server is out of scope.
- **Mac/Linux/Windows terminal UI**: A production-grade desktop merchant terminal with GUI is out of scope. The Python reference emitter provides command-line testing only.

## Further Notes

- The v0 prototype code in `android/app/src/main/java/com/example/blechat/` and `python/upi_server.py` implements the inverted topology (phone as GATT Server, Mac as GATT Client). These files should be deleted or moved to an archive directory as part of v1 implementation — they are not the starting point for new work.
- The new Service UUID must be registered and documented in the Wire Protocol RFC before SDK development begins, to avoid UUID collisions with v0.
- The compression dictionary should be versioned. The first version covers the top ~128 bank handles. A dictionary version byte in the protocol header allows future expansion without breaking existing SDKs.
- BLE 5.0 Extended Advertising is required for the full 64-byte signature in a single frame. Legacy BLE 4.2 devices fall back to multi-frame chunking (mode `0x01`) which adds ~60ms assembly latency.
