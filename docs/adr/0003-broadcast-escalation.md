# Broadcast-first payload delivery with GATT escalation as last resort

**Status**: accepted

The merchant delivers payment payloads over BLE using a layered escalation strategy: compression first, then multi-frame chunking, with GATT connection only as the final fallback. The goal is to keep the merchant in broadcast mode for as many transactions as possible — avoiding the concurrency bottlenecks and connection handshake latency of 1-to-1 GATT.

## Escalation hierarchy

1. **Dictionary Compression**: A static RFC-standardized dictionary maps the top ~128 Indian bank handles (e.g., `@sbi`, `@okaxis`, `@ybl`) to 1-byte index codes (0x01–0x64). A typical 40-character VPA shrinks to ~20 bytes, fitting in a single legacy BLE 4.x advertising frame (31 bytes). Instant delivery, unlimited concurrent customers.
2. **Multi-Frame Chunking**: If compression alone can't fit the payload, the merchant cycles alternating broadcast frames carrying sequential chunks. A 2–3 frame cycle assembles in ~50–100ms — still broadcast mode, still unlimited concurrency.
3. **GATT Connection Fallback**: Only triggered for payloads that exceed broadcast limits (e.g., a 500-byte itemized receipt). The customer phone connects as a GATT Client to the merchant's GATT Server, reads the characteristic, and disconnects immediately. This is 1-to-1 and serial — used sparingly.

## Header flag encoding

The Payload Mode flag (bits 0–1 of byte 0 in the binary payload) signals the delivery mode:

- `0x00` — Single-frame broadcast (compression successful)
- `0x01` — Multi-frame chunked broadcast
- `0x02` — GATT escalation required

The customer SDK reads this flag and follows the corresponding receive path without needing out-of-band negotiation.

## Consequences

- Merchants operating in broadcast mode (modes 0x00 and 0x01) can serve unlimited concurrent customers with no connection table pressure.
- The static compression dictionary must be identical across all SDK implementations and ESP32 firmware — versioned and shipped with the Wire Protocol RFC.
- Unknown bank handles (not in the dictionary) use the `0x00` raw-suffix escape, falling through to multi-frame chunking automatically.
