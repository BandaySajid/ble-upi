# 06 — Python Reference Emitter

**What to build:** A Python CLI tool using bleak that broadcasts valid Wire Protocol advertising payloads with a dev test keypair. SDK developers and QA engineers run this on a laptop to test the Customer SDK without needing an ESP32 or a second phone.

**Blocked by:** 02 — Wire Protocol RFC + compression dictionary

**Status:** ready-for-agent

- [ ] `python/emit_test.py` accepts CLI flags: `--vpa`, `--amount`, `--name`, `--mode` (single/multi/raw), `--key` (path to dev Ed25519 private key)
- [ ] Generates a valid Ed25519 dev keypair if none provided (printed to stdout for test use)
- [ ] Encodes the payload per RFC for the chosen Payload Mode and broadcasts it using bleak
- [ ] Broadcasts on the v1 protocol Service UUID (from RFC), not the old v0 UUID
- [ ] `python/requirements.txt` updated: remove `aiohttp`, keep `bleak>=0.21.0`, add `cryptography` for Ed25519 operations
- [ ] Archival v0 scripts (`upi_server.py`, `scan_test.py`, `index.html`, `README.md`) moved to `archive/v0/` or deleted
- [ ] Dev test instructions in `python/README.md` covering virtual environment setup, key generation, and running the emitter
- [ ] Emitter exits cleanly on Ctrl+C — no lingering BLE adapters or orphaned advertisements
