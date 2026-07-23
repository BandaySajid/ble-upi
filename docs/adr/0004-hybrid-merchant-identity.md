# Hybrid merchant identity resolution: in-band certificate + local cache

**Status**: accepted

The customer app must resolve the merchant's identity (display name, VPA, logo, public key) from a 32-bit short hash in the broadcast payload — offline. A pure local cache fails on first visits. Pure in-band broadcast can't carry rich assets like logos. The hybrid model combines both.

## Architecture

### Fast path (cache hit)
The customer app receives bytes 2–5 (32-bit Merchant Short Hash) and queries the on-device SQLite cache. If found, it instantly renders the merchant's name, logo, and category icon, and uses the cached public key for signature verification. No BLE certificate parsing needed.

### Fallback path (cache miss / first visit)
If the hash is not in cache, the app reads the **Compact Merchant Certificate** from the BLE 5.0 Extended Advertising frame (or 2-packet multi-frame stream in legacy mode). The certificate contains:
- Merchant VPA (ASCII)
- Merchant Display Name (UTF-8)
- Merchant Public Key (Ed25519 / secp256r1, 32 bytes)
- Payment Authority Signature over the above (RSA/ECDSA)

The app verifies the certificate chain against the **Root CA Public Key** hardcoded in the SDK binary, extracts the identity and public key, displays a generic category icon, and saves the profile to local cache for future instant lookups.

## Collision handling

A 32-bit hash has ~4.3B possible values — collisions across millions of merchants nationally are possible but within a 1-meter BLE radius are near-zero. If a collision does occur, the unique VPA and Public Key in the verified in-band certificate disambiguate.

## Consequences

- The Customer SDK must ship with a hardcoded Root CA Public Key and maintain a local merchant profile cache.
- Cache entries persist across app restarts and are updated opportunistically during background sync.
- Merchant logos are only available for cached profiles; first visits show a generic category icon until the next background sync.
