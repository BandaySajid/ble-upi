# BLE UPI Python Reference Tools

## emit_test.py — Reference Merchant Emitter

Broadcasts Wire Protocol v1 BLE advertisements for testing the Customer SDK.

### Setup

```bash
cd python
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

### Usage

Basic:
```bash
python emit_test.py --vpa shop@okaxis --amount 150.00 --name "Ram General Store"
```

Custom key:
```bash
python emit_test.py --vpa shop@okaxis --amount 25.50 --key merchant_key.bin
```

Raw VPA (uncommon bank):
```bash
python emit_test.py --vpa kirana@rarebank --amount 50
```

### Key Generation

If `--key` not provided, generates a new Ed25519 keypair saved to `merchant_key.bin`.

### Platform Notes

- **Linux/BlueZ**: Requires BlueZ >= 5.53 with `--experimental` flag for BLE advertising
- **macOS**: BLE advertising via CoreBluetooth limited to iBeacon-compatible formats — extended advertising not supported by all adapters
- **Windows**: bleak uses WinRT BLE — advertising support varies by adapter
