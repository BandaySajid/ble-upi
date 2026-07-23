# BLE UPI Proximity Payments

A contactless retail payment system where a merchant's device broadcasts a cryptographically signed UPI payment request over BLE, and nearby customer phones passively scan, verify, and present the payment — no QR code scanning required.

## Language

### Actors

**Merchant Terminal**:
A device that broadcasts BLE payment beacons. Can be a dedicated ESP32 hardware pod or a shopkeeper's Android phone running in Virtual Terminal mode.
_Avoid_: POS, merchant app, transmitter

**Customer Phone**:
A consumer's smartphone running a payment app (PhonePe, GPay, Paytm, etc.) with the protocol SDK integrated. Operates strictly as a passive BLE scanner — never advertises.
_Avoid_: User device, receiver, client

**Payment Authority**:
The central governing body (e.g., NPCI) that issues and signs merchant certificates, maintains the static compression dictionary, and publishes the Root CA Public Key.
_Avoid_: Backend, server, registry

### Architecture

**Wire Protocol (Tier 1)**:
The language-agnostic data specification defining how payment payloads are packed into bytes, signed, and transmitted over BLE. Implemented by firmware developers on microcontrollers and POS hardware.
_Avoid_: Low-level spec, RFC (acceptable shorthand), binary format

**High-Level SDK (Tier 2)**:
The platform-specific library (Kotlin for Android, Swift for iOS) wrapping all BLE scanning, crypto verification, certificate parsing, RSSI filtering, cooldown management, and UPI hand-off. Imported by payment app developers.
_Avoid_: Runtime, engine, wrapper

### Topology

**Broadcast Mode**:
The merchant emits BLE advertising packets that any number of customer phones receive simultaneously. The default and preferred delivery mode — connectionless, one-to-many, no concurrency limits.
_Avoid_: Advertising mode, beacon mode

**GATT Escalation**:
The fallback delivery mode triggered only when a payload exceeds broadcast limits. The customer phone connects to the merchant's GATT Server as a GATT Client, reads the oversized payload, and disconnects immediately. Serial, one-to-one, connection-capped — used sparingly.
_Avoid_: Connection mode, paired mode

**Virtual Terminal**:
A software-only merchant deployment where the shopkeeper's Android phone acts as the BLE beacon using `BluetoothLeAdvertiser` — no ESP32 hardware required.
_Avoid_: SoftPOS, phone beacon, app-only mode

**Hardware Pod**:
A dedicated low-cost BLE microcontroller (ESP32 or similar) that broadcasts payment beacons. Provisioned by the merchant companion app.
_Avoid_: Soundbox, beacon device, dongle

### Payload

**Transaction Payload**:
The binary packet broadcast by the merchant. Contains protocol header, merchant short hash, VPA suffix byte, amount in paisa, time-based nonce, 64-byte signature, and TX power calibration byte.
_Avoid_: Payment request, UPI string, broadcast frame

**Merchant Short Hash**:
A 32-bit truncated hash of the merchant's unique account identifier, used by the customer phone as a cache lookup key to resolve the merchant profile locally.
_Avoid_: Merchant ID, store hash

**Compression Dictionary**:
A static lookup table shipped inside the Wire Protocol RFC mapping the top ~128 Indian bank handles (e.g., `@sbi`, `@okaxis`) to immutable 1-byte index codes. Identical across all SDK and firmware builds. Unknown handles use `0x00` raw-suffix escape.
_Avoid_: Bank code table, VPA map

**Multi-Frame Chunking**:
Splitting a payload across 2–3 alternating BLE broadcast frames when compression alone can't fit it in one frame. The customer phone reassembles in memory. Still broadcast mode — no GATT connection.
_Avoid_: Fragmentation, segmented broadcast

**Payload Mode**:
A 2-bit flag in the protocol header (byte 0) signaling the delivery method: `0x00` single-frame broadcast, `0x01` multi-frame chunked broadcast, `0x02` GATT escalation.
_Avoid_: Delivery flag, transport mode

**Nonce**:
A 32-bit sliding window counter (UNIX timestamp ÷ 30 seconds) embedded in every broadcast. Forces packets to expire within 30 seconds, preventing replay attacks.
_Avoid_: Timestamp, counter, freshness token

**TX Power Byte**:
A mandatory 1-byte field declaring the broadcaster's calibrated radio transmission power at 1 meter, used by the customer SDK for RSSI-based proximity filtering.
_Avoid_: Signal calibration, RSSI reference

### Identity & Security

**Compact Merchant Certificate**:
A digitally signed blob (by the Payment Authority) containing the merchant's VPA, display name, and public key. Transmitted in-band via BLE 5.0 Extended Advertising. Verified offline by the customer phone against the hardcoded Root CA Public Key.
_Avoid_: Merchant cert, identity payload

**Root CA Public Key**:
The Payment Authority's master public key, hardcoded into every Customer SDK binary at build time. Used to verify the certificate chain on incoming merchant certificates — no network call required.
_Avoid_: Master key, authority key, trust anchor

**Merchant Private Key**:
Generated inside the merchant device's hardware secure storage during provisioning. Never leaves the device. Used to sign every transaction payload.
_Avoid_: Secret key, signing key

### Proximity

**RSSI Distance Filter**:
A signal-processing pipeline (Kalman filter or moving average) applied by the customer SDK to raw BLE RSSI values to determine whether the phone is within ~1 meter of the merchant — suppressing false triggers from devices at the far end of the room.
_Avoid_: Proximity check, range gate

**Cooldown**:
A time window after a payment request is displayed during which the customer phone ignores repeat broadcasts with the same transaction nonce, preventing notification spam while the customer reviews the bill.
_Avoid_: Dedup window, debounce, rate limit
