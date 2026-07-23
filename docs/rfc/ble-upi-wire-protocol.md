# BLE UPI Wire Protocol v1

**Status:** Draft  
**Audience:** Firmware developers, SDK authors, security reviewers  
**Revision:** 1.0.0

---

## 1. Overview

The BLE UPI Wire Protocol defines how a merchant payment beacon encodes a UPI payment request into a BLE advertising payload, signs it with an Ed25519 private key, and broadcasts it for passive reception by any number of customer devices. The protocol is language-agnostic and connectionless — no GATT pairing, no handshake, no per-device channels.

A customer device scans for advertisements matching the protocol's Service UUID, decodes the binary payload, verifies the Ed25519 signature using the merchant's public key (obtained from an in-band compact certificate or a local cache), validates freshness via a time-based nonce, and presents the payment request to the user.

---

## 2. Service UUID

```
4F425031-0001-4000-8000-000000000000
```

The 96-bit namespace suffix spells `"OBP1"` in ASCII (bytes 0x4F, 0x42, 0x50, 0x31) followed by the version byte `0x01` at position 0. The remaining bytes follow RFC 4122 UUID structure.

All v1 beacons advertise this UUID in the BLE Service UUID AD type (`0x07`). Consumer SDKs scan exclusively for this UUID.

**Rationale:** The old v0 prototyping UUID (`6e400001-b5a3-f393-e0a9-e50e24dcca9e`) is the Nordic UART Service clone — no collision with v1.

---

## 3. Payload Layout

BLE 5.0 Extended Advertising (ADV_EXT_IND) supports up to **255 bytes** of advertising data. This protocol uses one AD structure with the Service UUID in its standard location and the protocol payload as manufacturer-specific data (`AD type 0xFF`). Total maximum payload including header: 255 − 16 (UUID AD structure overhead) = **239 bytes available**.

All multi-byte fields are **big-endian** unless stated otherwise.

```
Offset  Bytes  Field                       Description
─────────────────────────────────────────────────────────────────────
0       1      Protocol Version & Flags    Bits 7-2: Version (v1 = 0b000001)
                                           Bits 1-0: Payload Mode
1       1      Dictionary Version          Compression dictionary version (v1 = 0x01)
2       4      Merchant Short Hash         SHA-256(PublicKey)[0:4] truncated to 32 bits
6       1      VPA Handle Dictionary Suffix  0x01–0x80: compressed index
                                            0x00: raw ASCII suffix follows in frame
7       3      Transaction Amount          Unsigned 24-bit integer, in paisa
10      2      Amount Fraction             Unsigned 16-bit integer, 1/1000 paisa (reserved, = 0)
12      4      Time-Based Nonce            ⌊UNIX time / 30⌋ as 32-bit uint
16      64     Ed25519 Signature           64 bytes, over domain separator + bytes 0–15
80      1      TX Power Calibration        Signed 8-bit dBm, per RFC-allowable range (−127 to +127)
81      var    Compact Merchant Certificate CBOR-encoded (see §6), variable length
```

### 3.1 Protocol Version & Flags (Byte 0)

```
Bit [7..2]  Version (0b000001 = v1)
Bit [1..0]  Payload Mode
```

**Payload Mode:**
| Bits | Mode | Description |
|------|------|-------------|
| `00` | **Single-Frame** | Full payload fits in one ADV_EXT_IND frame. VPA uses compression dictionary. |
| `01` | **Multi-Frame Chunked** | Payload split across alternating broadcast frames. Customer reassembles in memory. |
| `10` | **GATT Escalation** | Payload too large for any broadcast mode. Customer connects as GATT Client to read full payload. |
| `11` | Reserved | |

### 3.2 Dictionary Version (Byte 1)

Identifies which compression dictionary the payload was encoded with. `0x01` = v1 dictionary (this document, §5). SDKs must reject payloads with an unrecognized dictionary version.

### 3.3 Merchant Short Hash (Bytes 2–5)

`SHA-256(merchant_public_key_bytes)[0:4]` — the first 4 bytes of the SHA-256 hash of the merchant's Ed25519 public key (32 bytes raw), interpreted as a 32-bit big-endian unsigned integer. Used as a fast cache lookup key for returning customers. Collisions are rare (birthday bound ≈ 2^16 merchants) and handled by the SDK falling through to full certificate verification.

### 3.4 VPA Handle Dictionary Suffix (Byte 6)

`0x01` through `0x80` → index into the compression dictionary (§5). The VPA handle suffix is the substring after `@` (e.g., `@okaxis` → `okaxis`).

`0x00` → **Raw ASCII suffix**. The VPA suffix follows immediately after the certificate field with a 1-byte length prefix indicating the number of ASCII bytes.

### 3.5 Transaction Amount (Bytes 7–9)

24-bit unsigned integer in **paisa** (1/100 INR). Maximum representable amount: `0xFFFFFF` = 16,777,215 paisa = **₹167,772.15**.

A value of `0x000000` means **"no amount set"** (open-ended: customer enters amount).

Bytes 10–11 (`Amount Fraction`) are reserved for future fractional paisa support and must be `0x0000` in v1.

### 3.6 Time-Based Nonce (Bytes 12–15)

`⌊current_unix_seconds / 30⌋` as a 32-bit unsigned integer, big-endian. Each nonce value represents a 30-second window.

**Freshness validation:** Payloads with a nonce more than 6 windows (180 seconds) behind the current window are rejected as expired. This is local-only — no network time sync required, no NTP dependency. The customer device's local clock is the time source.

Future nonces (more than 1 window ahead) are also rejected.

### 3.7 Ed25519 Signature (Bytes 16–79)

Standard Ed25519 signature (64 bytes, RFC 8032).

**Signing input:** `"OBP1"` (4 ASCII bytes) concatenated with the raw bytes from offset 0 through 15 (16 bytes total).

```
signing_input = 0x4F425031 || payload[0..15]
signature     = Ed25519_Sign(merchant_private_key, signing_input)
```

The domain separator `"OBP1"` prevents cross-protocol signature replay: a valid v1 signature will not verify under any other protocol's key material or context.

**Customer verifies:** `Ed25519_Verify(merchant_public_key, signing_input, signature)`.

### 3.8 TX Power Calibration (Byte 80)

The BLE TX power level (dBm) at which this advertisement was transmitted, as a signed 8-bit integer (−127 to +127). Populated by querying the device's BLE adapter for its current TX power setting. **Not hardcoded.**

Customer SDKs use this value together with the measured RSSI to estimate path loss and classify proximity (near/far). See ADR-0001 and the RSSI filtering specification in the SDK documentation.

### 3.9 Compact Merchant Certificate (Bytes 81+)

CBOR-encoded structure used for first-visit merchant identity resolution:

```cbor
{
  0x01: bytes .size 32   ; public_key: Ed25519 public key (32 bytes raw)
  0x02: tstr             ; vpa: full UPI VPA (e.g., "shop@okaxis")
  0x03: tstr             ; display_name: human-readable merchant name
  0x04: bytes .size 64   ; ca_signature: Ed25519 signature over cert bytes by Root CA
}
```

**Map key 0x01** = public_key, **0x02** = vpa, **0x03** = display_name, **0x04** = ca_signature.

The CA signature signing input is: `"OBP1-CERT"` concatenated with the CBOR-encoded map (without key 0x04). Customer verifies with the hardcoded Root CA public key.

Maximum certificate size: ~200 bytes for typical VPA + display name — fits comfortably in Single-Frame mode.

---

## 4. Multi-Frame Chunking (Payload Mode 0x01)

When the complete payload exceeds a single ADV_EXT_IND frame (239 bytes of manufacturer data), the payload is split across multiple alternating broadcast frames.

Each chunk:

```
Offset  Bytes  Field
────────────────────────
0       1      Chunk Header
               Bits 7-4: Chunk Index (0-based)
               Bits 3-0: Total Chunks (1–15)
1       N      Payload Fragment
```

**Reassembly:** Customer SDK buffers chunks by `(device MAC + sequence)`. When all chunks arrive and contiguous bytes total the expected certificate length, the SDK reassembles and verifies.

**Timeout:** If not all chunks arrive within 3 seconds of the first chunk, the partial assembly is discarded.

**Legacy BLE 4.2:** On hardware without Extended Advertising support (31-byte legacy advertisements), multi-frame chunking is the only path for 64-byte signatures. Chunks alternate between scan response (31 bytes) and advertisement payload (31 bytes), giving ~30 bytes of payload per chunk. Assembly latency ≈ 60ms for typical 3-chunk splits.

---

## 5. Compression Dictionary v1

A static, immutable lookup table mapping the top 128 UPI bank/VPA handle suffixes to 1-byte index codes. All SDK builds and firmware images embed this identical table.

| Index | Handle | Parent Bank / App |
|-------|--------|-------------------|
| 0x01 | `okaxis` | Axis Bank |
| 0x02 | `okhdfcbank` | HDFC Bank |
| 0x03 | `okicici` | ICICI Bank |
| 0x04 | `oksbi` | State Bank of India |
| 0x05 | `ybl` | Yes Bank |
| 0x06 | `paytm` | Paytm Payments Bank |
| 0x07 | `upi` | NPCI (generic) |
| 0x08 | `ibl` | IDFC FIRST Bank |
| 0x09 | `axl` | Airtel Payments Bank |
| 0x0A | `fbl` | Fino Payments Bank |
| 0x0B | `yesbank` | Yes Bank (legacy) |
| 0x0C | `idfcbank` | IDFC FIRST Bank (legacy) |
| 0x0D | `barodampay` | Bank of Baroda |
| 0x0E | `pnb` | Punjab National Bank |
| 0x0F | `cbin` | Central Bank of India |
| 0x10 | `canarabank` | Canara Bank |
| 0x11 | `indianbank` | Indian Bank |
| 0x12 | `kotak` | Kotak Mahindra Bank |
| 0x13 | `unionbank` | Union Bank of India |
| 0x14 | `iob` | Indian Overseas Bank |
| 0x15 | `federal` | Federal Bank |
| 0x16 | `dbs` | DBS Bank |
| 0x17 | `hsbc` | HSBC |
| 0x18 | `citi` | Citibank |
| 0x19 | `idbi` | IDBI Bank |
| 0x1A | `indus` | IndusInd Bank |
| 0x1B | `bandhan` | Bandhan Bank |
| 0x1C | `csb` | CSB Bank |
| 0x1D | `dhanlaxmi` | Dhanlaxmi Bank |
| 0x1E | `jandhan` | Jana Small Finance Bank |
| 0x1F | `karnataka` | Karnataka Bank |
| 0x20 | `karurvysya` | Karur Vysya Bank |
| 0x21 | `rbl` | RBL Bank |
| 0x22 | `sib` | South Indian Bank |
| 0x23 | `uco` | UCO Bank |
| 0x24 | `allbank` | Allahabad Bank |
| 0x25 | `andhra` | Andhra Bank |
| 0x26 | `boi` | Bank of India |
| 0x27 | `bom` | Bank of Maharashtra |
| 0x28 | `corporation` | Corporation Bank |
| 0x29 | `dena` | Dena Bank |
| 0x2A | `ezeepay` | Ezeepay |
| 0x2B | `finobank` | Fino Payments Bank (alt) |
| 0x2C | `hdfc` | HDFC Bank (alt) |
| 0x2D | `icici` | ICICI Bank (alt) |
| 0x2E | `imobile` | ICICI iMobile |
| 0x2F | `mahb` | Bank of Maharashtra (alt) |
| 0x30 | `obc` | Oriental Bank of Commerce |
| 0x31 | `pingpay` | Ping Pay |
| 0x32 | `pockets` | ICICI Pockets |
| 0x33 | `psb` | Punjab & Sind Bank |
| 0x34 | `purz` | Purz |
| 0x35 | `sbi` | State Bank of India (alt) |
| 0x36 | `sc` | Standard Chartered |
| 0x37 | `sib` | South Indian Bank (alt) |
| 0x38 | `synd` | Syndicate Bank |
| 0x39 | `tjsb` | TJSB Bank |
| 0x3A | `ubi` | United Bank of India |
| 0x3B | `united` | United Bank of India (alt) |
| 0x3C | `vijaya` | Vijaya Bank |
| 0x3D | `wb` | West Bengal |
| 0x3E | `yesbnk` | Yes Bank (alt) |
| 0x3F | `airtel` | Airtel Payments Bank (alt) |
| 0x40 | `amazon` | Amazon Pay |
| 0x41 | `free` | Freecharge |
| 0x42 | `jiomart` | JioMart |
| 0x43 | `mobikwik` | Mobikwik |
| 0x44 | `phonepe` | PhonePe |
| 0x45 | `slice` | Slice |
| 0x46 | `tapicash` | Tapi Cash |
| 0x47 | `timecosmos` | Time Cosmos |
| 0x48 | `zomato` | Zomato |
| 0x49 | `abcd` | ABCDEF |
| 0x4A | `abfspay` | ABFS Pay |
| 0x4B | `airtelpay` | Airtel Payments Bank (alt) |
| 0x4C | `albank` | Allahabad Bank (alt) |
| 0x4D | `andb` | Andhra Bank (alt) |
| 0x4E | `apay` | Amazon Pay (alt) |
| 0x4F | `apl` | Apple (reserved) |
| 0x50 | `aufin` | AU Small Finance Bank |
| 0x51 | `bdb` | Bandhan Bank (alt) |
| 0x52 | `bob` | Bank of Baroda (alt) |
| 0x53 | `cboi` | Central Bank of India (alt) |
| 0x54 | `central` | Central Bank of India (alt) |
| 0x55 | `chqbook` | Chqbook |
| 0x56 | `cityunion` | City Union Bank |
| 0x57 | `cnrb` | Canara Bank (alt) |
| 0x58 | `cosmos` | Cosmos Bank |
| 0x59 | `cub` | City Union Bank (alt) |
| 0x5A | `dcb` | DCB Bank |
| 0x5B | `dcbbank` | DCB Bank (alt) |
| 0x5C | `demobank` | Reserved (demo/testing) |
| 0x5D | `digibank` | DBS digibank |
| 0x5E | `equitas` | Equitas Small Finance Bank |
| 0x5F | `esaf` | ESAF Small Finance Bank |
| 0x60 | `fdrl` | Federal Bank (alt) |
| 0x61 | `finokwik` | Fino Payments Bank (alt) |
| 0x62 | `gkash` | Gkash |
| 0x63 | `gpay` | Google Pay |
| 0x64 | `idbibank` | IDBI Bank (alt) |
| 0x65 | `ind` | Indian Bank (alt) |
| 0x66 | `indie` | Indie by IndusInd |
| 0x67 | `jio` | Jio Payments Bank |
| 0x68 | `jkb` | Jammu & Kashmir Bank |
| 0x69 | `jsfb` | Jana SFB (alt) |
| 0x6A | `kbl` | Karnataka Bank (alt) |
| 0x6B | `kotak811` | Kotak 811 |
| 0x6C | `kvb` | Karur Vysya Bank (alt) |
| 0x6D | `lime` | Lime / PayLater |
| 0x6E | `lvb` | Lakshmi Vilas Bank |
| 0x6F | `mpay` | mPay |
| 0x70 | `nmb` | Nainital Bank |
| 0x71 | `nsdl` | NSDL Payments Bank |
| 0x72 | `obc` | Oriental Bank (alt) |
| 0x73 | `okbiz` | HDFC Biz |
| 0x74 | `okcredit` | OkCredit |
| 0x75 | `paytmqr` | Paytm QR |
| 0x76 | `pockets` | ICICI Pockets (alt) |
| 0x77 | `purzpay` | Purz Pay |
| 0x78 | `rajgov` | Rajasthan Government |
| 0x79 | `shivalik` | Shivalik SFB |
| 0x7A | `slicepay` | Slice (alt) |
| 0x7B | `suryoday` | Suryoday SFB |
| 0x7C | `tmb` | Tamilnad Mercantile Bank |
| 0x7D | `ubi` | Union Bank (alt) |
| 0x7E | `uco` | UCO Bank (alt) |
| 0x7F | `utkarsh` | Utkarsh SFB |
| 0x80 | `yesbankpay` | Yes Bank Pay |
| 0x00 | — | **Raw ASCII suffix** (uncompressed, length-prefixed) |

**Index 0x5C (`demobank`)** is reserved for development and testing only. Production beacons must not use it.

**Dictionary expansion:** Future dictionary versions can add entries up to index 0xFE. `0x00` is permanently reserved for raw mode. `0xFF` is reserved.

---

## 6. Cryptographic Specification

### 6.1 Key Generation

Merchant generates an Ed25519 keypair:
- **Private key:** 32 bytes, stored in platform secure storage (Android KeyStore for Virtual Terminal, ESP32 eFuse/NVS for hardware pods)
- **Public key:** 32 bytes raw (RFC 8032 format)

Public key is embedded in the Compact Merchant Certificate (§3.9) and signed by the Payment Authority Root CA.

### 6.2 Signature

```
Signing Input = "OBP1" || payload_bytes[0..15]
Signature = Ed25519_Sign(private_key, Signing Input)
```

The `||` operator denotes byte concatenation. `"OBP1"` encodes as bytes `[0x4F, 0x42, 0x50, 0x31]`.

Total signing input: 20 bytes.

### 6.3 Verification

Customer reconstructs `signing_input` from received payload bytes 0–15, extracts signature from bytes 16–79, and:

```
Ed25519_Verify(merchant_public_key, signing_input, signature) → boolean
```

If verification fails, the payload is silently discarded — no error callback to avoid DoS vector.

### 6.4 Certificate Verification

The Compact Merchant Certificate (CBOR map) contains a CA signature. Customer verifies:

```
cert_signing_input = "OBP1-CERT" || cbor_encode(cert_map_without_key_0x04)
Ed25519_Verify(root_ca_public_key, cert_signing_input, ca_signature) → boolean
```

Root CA public key is hardcoded in the SDK at compile time. During development, a test key is used.

### 6.5 Development Root CA Keypair

**Private key (dev only — never in production):**
```
b4e6c5d4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e0afbecd
```

**Public key (hardcoded in dev SDK builds):**
```
a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2
```

These are notional placeholders. Production keys are issued by the Payment Authority.

---

## 7. Test Vectors

All test vectors use the dev keypair from §6.5. Values are hex-encoded.

### 7.1 Single-Frame (Mode 0x00) — Full Payload

**Input:**
- VPA: `shop@okaxis`
- Display name: `Ram General Store`
- Amount: ₹150.00 (15000 paisa)
- Nonce: window 1823456
- Dictionary version: 1

**Payload bytes (hex):**
```
04 01                                      # Byte 0-1: Version 1, Mode 0x00, Dict v1
A1 B2 C3 D4                                # Bytes 2-5: Merchant short hash (example)
01                                          # Byte 6: VPA suffix index = @okaxis
00 3A 98                                    # Bytes 7-9: 15000 paisa in 24-bit BE
00 00                                       # Bytes 10-11: Amount fraction = 0
00 1B D5 60                                 # Bytes 12-15: Nonce window 1823456
[SIG 64 bytes]                              # Bytes 16-79: Ed25519 signature
E6                                          # Byte 80: TX Power = -26 dBm (0xE6)
A4 01 58 20 [PK 32 bytes]                   # CBOR cert: public key
   02 6F 7368 6F70 406F 6B61 7869 73       # vpa = "shop@okaxis"
   03 72 5261 6D20 4765 6E65 7261 6C20     # display_name = "Ram General Store"
   53 746F 7265                             #
   04 58 40 [CA_SIG 64 bytes]              # ca_signature
```

### 7.2 Multi-Frame Chunked (Mode 0x01)

Same input as §7.1 but with a longer display name that pushes total size past single-frame limit.

**Frame 0 header byte:** `0x03` (index 0 of 4 total chunks)  
**Frame 1 header byte:** `0x13` (index 1 of 4 total chunks)  
**Frame 2 header byte:** `0x23` (index 2 of 4 total chunks)  
**Frame 3 header byte:** `0x33` (index 3 of 4 total chunks)

### 7.3 Raw VPA Suffix (Mode 0x00, Suffix = 0x00)

**Input:**
- VPA: `kirana@rarebank` (not in dictionary)
- Amount: ₹25.50 (2550 paisa)

**Payload suffix byte = `0x00`.** A 1-byte length (`0x08`) followed by 8 ASCII bytes `"rarebank"` appears after the certificate field.

### 7.4 Tampered Signature — Should Reject

Hex-identical to §7.1 except byte 20 (first byte of signature) is flipped from `0x5A` → `0x5B`. Verification must return `false`.

### 7.5 Expired Nonce — Should Reject

Hex-identical to §7.1 except nonce (bytes 12–15) set to current window minus 7 (i.e., 180+ seconds old). Verification must reject with `BleUpiError.ExpiredNonce`.

### 7.6 Wrong Domain Separator — Should Reject

Payload signed with domain separator `"OBP0"` instead of `"OBP1"`. Verification must return `false`.

---

## 8. Error Codes

Customer SDK surfaces the following errors via `BleUpiError`:

| Code | Name | Meaning |
|------|------|---------|
| 1 | `INVALID_PROTOCOL_VERSION` | Byte 0 version field ≠ v1 |
| 2 | `INVALID_PAYLOAD_MODE` | Bits 1-0 = reserved value (0x03) |
| 3 | `INVALID_DICTIONARY_VERSION` | Byte 1 ≠ known dictionary version |
| 4 | `EXPIRED_NONCE` | Nonce outside ±6 window from current |
| 5 | `SIGNATURE_INVALID` | Ed25519 verification failed |
| 6 | `CERT_INVALID` | CA signature on merchant certificate failed |
| 7 | `CERT_MISSING_PROFILE` | Certificate present but VPA/name/public_key field empty |
| 8 | `TRUNCATED_PAYLOAD` | Payload too short for minimum valid packet |
| 9 | `CHUNK_TIMEOUT` | Multi-frame chunks not fully assembled within 3s |
| 10 | `CHUNK_OVERFLOW` | Reassembly buffer exceeds maximum payload size |

---

## 9. Versioning

| Byte 0 bits [7:2] | Version | Status |
|-------------------|---------|--------|
| `0b000001` | v1 | Current (this document) |
| All others | — | Reserved |

Dictionary version (byte 1) is independently versioned. v1 dictionary = `0x01`.

---

## 10. References

- [RFC 8032 — Ed25519](https://datatracker.ietf.org/doc/html/rfc8032)
- [RFC 7049 — CBOR](https://datatracker.ietf.org/doc/html/rfc7049)
- [Bluetooth Core Specification 5.0 — Extended Advertising](https://www.bluetooth.com/specifications/specs/core-specification-5-0/)
- [ADR-0001 — Topology Inversion](../../docs/adr/0001-topology-inversion.md)
- [ADR-0002 — Asymmetric Offline Crypto](../../docs/adr/0002-asymmetric-offline-crypto.md)
- [ADR-0003 — Broadcast Escalation](../../docs/adr/0003-broadcast-escalation.md)
- [ADR-0004 — Hybrid Merchant Identity](../../docs/adr/0004-hybrid-merchant-identity.md)
