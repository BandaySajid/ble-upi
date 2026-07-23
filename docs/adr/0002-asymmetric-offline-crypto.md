# Use asymmetric cryptography (Ed25519 / secp256r1) for offline beacon verification

**Status**: accepted

Merchant beacons must be cryptographically verified by the customer's phone without an internet lookup at transaction time. We chose asymmetric public-key signatures (Ed25519 or secp256r1, full 64 bytes) over symmetric HMAC.

## Why not HMAC

1. **Symmetric HMAC requires online key fetch**: The customer's phone needs the merchant's secret key to verify. Fetching it from a server breaks offline operation. Pre-caching millions of symmetric keys on every phone is a catastrophic security leak if a single device is rooted.
2. **HMAC truncation is safe server-side (rate-limited) but signature truncation is not**: A 40-bit truncated HMAC forces an attacker to submit guesses to a server that can rate-limit. A 40-bit truncated ECDSA/Ed25519 signature can be brute-forced offline with a consumer GPU in under 60 seconds because the public key is known.

## Architecture

- **Key generation**: Merchant private key generated inside hardware secure storage (ESP32 eFuse/NVS or Android KeyStore) during provisioning. Never leaves the device.
- **Certificate issuance**: A central Payment Authority signs a compact certificate binding `[Merchant_ID + Public_Key]`. The Payment Authority's Root Public Key is hardcoded in the Customer SDK.
- **Signing**: Merchant signs the 14-byte payload (header + merchant-hash + VPA-suffix + amount + nonce) with its private key, producing a 64-byte Ed25519/secp256r1 signature. A 2-byte domain separator (`OBP1`) is prepended to the hash context to prevent cross-protocol signature replay.
- **Verification**: Customer phone extracts the merchant's public key from the in-band certificate, verifies the certificate chain against the hardcoded Root CA key, then verifies the 64-byte transaction signature — all local, zero network.

## Consequences

- BLE 5.0 Extended Advertising (`ADV_EXT_IND`, up to 255 bytes) is required to carry the full 64-byte signature in a single frame. Legacy BLE 4.2 can fall back to 3-frame multi-frame chunking (~60ms assembly).
- The 5-byte truncated HMAC field in the old 20-byte payload spec is replaced by the full 64-byte signature transmitted via extended advertising.
