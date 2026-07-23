# 03 — Protocol Core (payload codec + crypto)

**What to build:** The `:protocol-sdk` library can encode a `PaymentRequest` into a binary advertising payload, decode a raw byte array back into a domain object, and cryptographically verify that the payload was signed by a merchant whose public key chains to the hardcoded Root CA.

**Blocked by:** 02 — Wire Protocol RFC + compression dictionary

**Status:** ready-for-agent

- [ ] `PayloadEncoder` produces the exact binary layout per RFC for all three Payload Modes (single-frame, multi-frame, raw VPA fallback)
- [ ] `PayloadDecoder` parses a raw byte array back into a `PayloadHeader` + `PaymentRequest` with all fields populated, for all three modes
- [ ] `CryptoVerifier` accepts a `PaymentRequest` and a merchant public key, verifies Ed25519 signature over domain separator + bytes 0–13
- [ ] Round-trip encode/decode unit tests using RFC hex test vectors for every Payload Mode and edge case
- [ ] Known-answer signature verification tests: valid keypair → verify passes; tampered payload → verify fails; expired nonce (outside 180s window) → reject; wrong domain separator → reject
- [ ] Hardcoded development Root CA public key (placeholder, not a real CA) with documentation on where the production key will come from
- [ ] Compression dictionary embedded as a static lookup map per RFC v1, with fast encode/decode path
- [ ] All data types defined: `PayloadHeader`, `PaymentRequest`, `MerchantProfile`, `BleUpiError`
- [ ] No external dependencies beyond BouncyCastle (bundled) and `javax.crypto` — no network calls in codec or verifier
- [ ] Tests pass green with `./gradlew :protocol-sdk:test`
