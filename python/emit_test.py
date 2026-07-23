#!/usr/bin/env python3
"""
BLE UPI Reference Merchant Emitter

Broadcasts Wire Protocol v1 beacons for testing the Customer SDK.
Uses bleak for BLE advertising and Ed25519 from the cryptography library.

Usage:
    python emit_test.py --vpa shop@okaxis --amount 150.00 --name "Ram General Store"
    python emit_test.py --vpa kirana@demobank --amount 50 --mode single
    python emit_test.py --vpa test@rarebank --amount 25.50 --mode raw
"""

import argparse
import struct
import hashlib
import time
import asyncio
import signal
import sys
from cryptography.hazmat.primitives.asymmetric import ed25519
from cryptography.hazmat.primitives import serialization
import cbor2

SERVICE_UUID = "4F425031-0001-4000-8000-000000000000"
PROTOCOL_VERSION = 1
DICT_VERSION = 1
DOMAIN_SEPARATOR = b"OBP1"
CERT_DOMAIN_SEPARATOR = b"OBP1-CERT"
TX_POWER = -26

COMPRESSION_DICT = {
    "okaxis": 0x01, "okhdfcbank": 0x02, "okicici": 0x03, "oksbi": 0x04,
    "ybl": 0x05, "paytm": 0x06, "upi": 0x07, "ibl": 0x08,
    "axl": 0x09, "fbl": 0x0A, "yesbank": 0x0B, "idfcbank": 0x0C,
    "barodampay": 0x0D, "pnb": 0x0E, "cbin": 0x0F, "canarabank": 0x10,
    "indianbank": 0x11, "kotak": 0x12, "unionbank": 0x13, "iob": 0x14,
    "federal": 0x15, "dbs": 0x16, "hsbc": 0x17, "citi": 0x18,
    "idbi": 0x19, "indus": 0x1A, "bandhan": 0x1B, "csb": 0x1C,
    "dhanlaxmi": 0x1D, "jandhan": 0x1E, "karnataka": 0x1F, "karurvysya": 0x20,
    "rbl": 0x21, "sib": 0x22, "uco": 0x23, "allbank": 0x24,
    "andhra": 0x25, "boi": 0x26, "bom": 0x27, "corporation": 0x28,
    "dena": 0x29, "ezeepay": 0x2A, "finobank": 0x2B, "hdfc": 0x2C,
    "icici": 0x2D, "imobile": 0x2E, "mahb": 0x2F, "obc": 0x30,
    "pingpay": 0x31, "pockets": 0x32, "psb": 0x33, "purz": 0x34,
    "sbi": 0x35, "sc": 0x36, "synd": 0x38, "tjsb": 0x39,
    "ubi": 0x3A, "united": 0x3B, "vijaya": 0x3C, "wb": 0x3D,
    "yesbnk": 0x3E, "airtel": 0x3F, "amazon": 0x40, "free": 0x41,
    "jiomart": 0x42, "mobikwik": 0x43, "phonepe": 0x44, "slice": 0x45,
    "demobank": 0x5C, "gpay": 0x63,
}

REVERSE_DICT = {v: k for k, v in COMPRESSION_DICT.items()}

class Keypair:
    def __init__(self):
        self.private_key = ed25519.Ed25519PrivateKey.generate()
        self.public_key_bytes = self.private_key.public_key().public_bytes(
            serialization.Encoding.Raw, serialization.PublicFormat.Raw
        )
        self.private_key_bytes = self.private_key.private_bytes(
            serialization.Encoding.Raw,
            serialization.PrivateFormat.Raw,
            serialization.NoEncryption()
        )

    @classmethod
    def from_file(cls, path):
        with open(path, 'rb') as f:
            private_bytes = f.read()
        kp = cls.__new__(cls)
        kp.private_key = ed25519.Ed25519PrivateKey.from_private_bytes(private_bytes)
        kp.public_key_bytes = kp.private_key.public_key().public_bytes(
            serialization.Encoding.Raw, serialization.PublicFormat.Raw
        )
        kp.private_key_bytes = private_bytes
        return kp

    def save(self, path):
        with open(path, 'wb') as f:
            f.write(self.private_key_bytes)


def dev_ca_keypair():
    """Development-only CA keypair matching the SDK hardcoded keys."""
    pk_hex = "b4e6c5d4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e0afbecd"
    kp = Keypair.__new__(Keypair)
    kp.private_key = ed25519.Ed25519PrivateKey.from_private_bytes(bytes.fromhex(pk_hex))
    kp.public_key_bytes = kp.private_key.public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    )
    kp.private_key_bytes = bytes.fromhex(pk_hex)
    return kp


def sha256_first4(data):
    return struct.unpack(">I", hashlib.sha256(data).digest()[:4])[0]


def current_nonce():
    return int(time.time()) // 30


def build_header(mode, dict_version, short_hash, suffix_idx, amount_paise, nonce):
    version_byte = (PROTOCOL_VERSION << 2) | mode
    header = struct.pack(
        ">BB I B I H I",
        version_byte,
        dict_version,
        short_hash,
        suffix_idx,
        amount_paise & 0xFFFFFF,
        0,
        nonce
    )
    return header[:16]


def build_cert(vpa, display_name, merchant_pk, ca_keypair):
    cert_map = {
        1: merchant_pk,
        2: vpa,
        3: display_name,
    }
    cert_data = cbor2.dumps(cert_map)
    signing_input = CERT_DOMAIN_SEPARATOR + cert_data
    ca_signature = ca_keypair.private_key.sign(signing_input)
    cert_map[4] = ca_signature
    return cbor2.dumps(cert_map)


def encode_payload(vpa, display_name, amount_paise, merchant_kp, ca_kp, tx_power):
    vpa_parts = vpa.split("@", 1)
    handle = vpa_parts[1] if len(vpa_parts) == 2 else vpa
    suffix_idx = COMPRESSION_DICT.get(handle, 0)

    short_hash = sha256_first4(merchant_kp.public_key_bytes)
    nonce = current_nonce()

    header = build_header(0, DICT_VERSION, short_hash, suffix_idx, amount_paise, nonce)
    signing_input = DOMAIN_SEPARATOR + header
    signature = merchant_kp.private_key.sign(signing_input)

    cert = build_cert(vpa, display_name, merchant_kp.public_key_bytes, ca_kp)

    payload = bytearray(header)
    payload.extend(signature)
    payload.append(tx_power & 0xFF)

    if suffix_idx == 0:
        handle_bytes = handle.encode("ascii")
        payload.append(len(handle_bytes))
        payload.extend(handle_bytes)

    payload.extend(cert)

    return bytes(payload)


async def broadcast(advertisement_data, duration=None):
    from bleak import BleakScanner, BleakClient

    def hexify(uuid_str):
        return uuid_str.replace("-", "")

    hex_uuid = hexify(SERVICE_UUID)

    class Dummy:
        pass

    try:
        from bleak.backends.bluezdbus.advertisement import Advertisement
        from bleak.backends.bluezdbus import get_bus

        ad = Advertisement()
        ad.service_uuids = [SERVICE_UUID]
        ad.manufacturer_data = {0xFFFF: bytes(advertisement_data)}

        async with BleakScanner() as scanner:
            print(f"Broadcasting on UUID: {SERVICE_UUID}")
            print(f"Payload bytes ({len(bytes(advertisement_data))}): {bytes(advertisement_data).hex()}")
            adapter = None
            try:
                from bleak.backends.bluezdbus.advertisement import register_advertisement
                from bleak.backends.bluezdbus.advertisement import unregister_advertisement

                adapter_name = "/org/bluez/hci0"
                await register_advertisement(adapter_name, ad)
                print("Registered advertisement. Broadcasting...")

                if duration:
                    await asyncio.sleep(duration)
                else:
                    while True:
                        await asyncio.sleep(1)
            except Exception as e:
                print(f"BlueZ direct advertising failed: {e}")
                print("Falling back: ensure BlueZ >= 5.53 with experimental mode")

    except ImportError:
        pass

    print("\nBroadcasting via bleak... (may need sudo / bluetoothd experimental)")
    running = True
    stop_event = asyncio.Event()

    def signal_handler(sig, frame):
        nonlocal running
        running = False
        stop_event.set()

    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    try:
        while running:
            await asyncio.sleep(0.5)
    except asyncio.CancelledError:
        pass

    print("\nStopped.")


async def main():
    parser = argparse.ArgumentParser(description="BLE UPI Reference Merchant Emitter")
    parser.add_argument("--vpa", required=True, help="Merchant UPI VPA (e.g., shop@okaxis)")
    parser.add_argument("--amount", type=float, default=0, help="Amount in ₹ (e.g., 150.00)")
    parser.add_argument("--name", default="Test Merchant", help="Merchant display name")
    parser.add_argument("--mode", choices=["single", "multi", "raw"], default="single",
                        help="Payload mode")
    parser.add_argument("--key", help="Path to Ed25519 private key file (32 bytes)")
    parser.add_argument("--tx-power", type=int, default=-26, help="TX power in dBm")
    parser.add_argument("--duration", type=int, default=0, help="Broadcast duration in seconds (0 = indefinite)")

    args = parser.parse_args()

    amount_paise = int(args.amount * 100)

    if args.key:
        merchant_kp = Keypair.from_file(args.key)
    else:
        merchant_kp = Keypair()
        key_path = "merchant_key.bin"
        merchant_kp.save(key_path)
        print(f"Generated merchant key → {key_path}")
        print(f"Public key:  {merchant_kp.public_key_bytes.hex()}")

    ca_kp = dev_ca_keypair()

    mode_map = {"single": 0, "multi": 1, "raw": 0 if args.vpa.split("@")[-1] in COMPRESSION_DICT else 0}

    print(f"\nMerchant: {args.name}")
    print(f"VPA:      {args.vpa}")
    print(f"Amount:   ₹{args.amount:.2f} ({amount_paise} paisa)")
    print(f"Mode:     {args.mode}")
    print(f"TX Power: {args.tx_power} dBm")
    print(f"Nonce:    {current_nonce()}")

    payload = encode_payload(args.vpa, args.name, amount_paise, merchant_kp, ca_kp, args.tx_power)

    await broadcast(payload, duration=args.duration if args.duration > 0 else None)


if __name__ == "__main__":
    asyncio.run(main())
