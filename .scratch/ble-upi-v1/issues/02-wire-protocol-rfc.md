# 02 — Wire Protocol RFC + compression dictionary

**What to build:** A standalone RFC document at `docs/rfc/ble-upi-wire-protocol.md` that defines every byte and bit of the protocol payload, the compression dictionary, and the cryptographic signing scheme. This is the single source of truth that firmware developers, SDK authors, and test-writers all reference.

**Blocked by:** None — can start immediately (parallel with 01)

**Status:** completed

- [ ] Full binary payload layout defined with exact byte offsets, bit positions, and field widths
- [ ] Service UUID allocated and documented as the well-known protocol UUID
- [ ] Compression dictionary v1: ~128 UPI bank handles mapped to 1-byte index codes (`0x01`–`0x80`), `0x00` reserved for raw ASCII suffix fallback
- [ ] Payload Mode flags defined: `0x00` single-frame compressed, `0x01` multi-frame chunked, `0x02` GATT escalation
- [ ] Ed25519 signing scheme: domain separator `OBP1`, signing input range (bytes 0–13), signature placement (64 bytes)
- [ ] Time-based nonce: 32-bit UNIX ÷ 30s sliding window, 180s expiry (6 windows), local offline replay protection
- [ ] TX Power Calibration Byte semantics (RFC-allowable dBm range, how SDK uses it)
- [ ] Compact Merchant Certificate format (VPA + Display Name + Public Key + CA Signature) for in-band delivery
- [ ] Hex-encoded test vectors: at minimum one valid payload per Payload Mode, one tampered-signature case, one expired-nonce case
- [ ] BLE 5.0 Extended Advertising requirement stated; legacy BLE 4.2 fallback via multi-frame chunking documented
- [ ] Dictionary version byte specified for future expansion
