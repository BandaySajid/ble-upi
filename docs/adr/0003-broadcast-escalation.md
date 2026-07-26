# Broadcast-first payload delivery with extended advertising primary and GATT escalation as last resort

**Status**: accepted (amended 2026-07-26)

The merchant delivers payment payloads over BLE using a layered strategy: dictionary compression applied to all payloads first, BLE 5 Extended Advertising as the primary delivery path, legacy multi-frame chunking as fallback, with GATT connection only as the final fallback. The goal is to keep the merchant in broadcast mode for as many transactions as possible — avoiding the concurrency bottlenecks and connection handshake latency of 1-to-1 GATT.

## Escalation hierarchy

1. **Dictionary Compression** (always applied): A static RFC-standardized dictionary maps the top ~128 Indian bank handles (e.g., `@sbi`, `@okaxis`, `@ybl`) to 1-byte index codes, shrinking typical VPAs from ~40 bytes to ~20 bytes before any delivery mode is attempted.

2. **Extended Advertising (Primary)**: On devices supporting BLE 5+ Extended Advertising (`API ≥35`, payloads up to 255 bytes), the full compressed + signed payment payload is sent in a single advertising packet. Instant delivery with zero assembly delay — near-realtime customer reception.

3. **Legacy Multi-Frame Chunking (Fallback)**: On older devices, the payload is split into chunks and cycled at 30ms intervals (optimized from 100ms) with up to 24 bytes per chunk (optimized from 22). The customer scanner uses a 15-second periodic restart to prevent hardware-level scan batching drops. 5–7 chunks deliver in ~200ms.

4. **GATT Connection Fallback**: Only triggered for payloads that exceed broadcast limits (e.g., a 500-byte itemized receipt). The customer phone connects as a GATT Client to the merchant's GATT Server, reads the characteristic, and disconnects immediately. This is 1-to-1 and serial — used sparingly.

## Header flag encoding

The Payload Mode flag (bits 0–1 of byte 0 in the binary payload) signals the delivery mode:

- `0x00` — Single-frame broadcast (extended advertising, near-instant)
- `0x01` — Multi-frame chunked broadcast (legacy fallback)
- `0x02` — GATT escalation required

The customer SDK reads this flag and follows the corresponding receive path without needing out-of-band negotiation.

## Consequences

- Extended advertising delivers payment requests near-instantly (sub-second) on modern devices, eliminating the 3–5 second delay experienced with legacy multi-chunk cycling.
- Merchants operating in broadcast mode (modes 0x00 and 0x01) can serve unlimited concurrent customers with no connection table pressure.
- Legacy fallback (30ms interval, 24-byte chunks, 15s scan restart) ensures compatibility with all BLE 4.x+ devices.
- The static compression dictionary must be identical across all SDK implementations and ESP32 firmware — versioned and shipped with the Wire Protocol RFC.
- Unknown bank handles (not in the dictionary) use the `0x00` raw-suffix escape, falling through to multi-frame chunking automatically.
