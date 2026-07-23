#!/usr/bin/env python3
"""
ESP32 Pod Mock — GATT server that simulates a merchant hardware pod.

Accepts provisioning writes (VPA, display name, Ed25519 private key) and
amount updates from the Merchant App Companion Dashboard.

Usage:
    python pod_mock.py
"""

import asyncio
import signal
import sys
from bleak import BleakServer, BleakGATTCharacteristic, BleakGATTService
from bleak.uuids import uuid16_dict

POD_SERVICE_UUID = "4F425031-0002-4000-8000-000000000000"
VPA_CHAR_UUID = "4F425031-0003-4000-8000-000000000001"
DISPLAY_NAME_CHAR_UUID = "4F425031-0003-4000-8000-000000000002"
PRIVATE_KEY_CHAR_UUID = "4F425031-0003-4000-8000-000000000003"
AMOUNT_CHAR_UUID = "4F425031-0003-4000-8000-000000000004"
BATTERY_CHAR_UUID = "4F425031-0003-4000-8000-000000000005"

state = {
    "vpa": b"",
    "display_name": b"",
    "private_key": b"",
    "amount_paise": b"\x00\x00\x00\x00",
    "battery": 85
}

def vpa_read(characteristic, data):
    return state["vpa"]

def vpa_write(characteristic, data):
    state["vpa"] = data
    print(f"[Pod] VPA set: {data.decode('utf-8', errors='replace')}")

def name_write(characteristic, data):
    state["display_name"] = data
    print(f"[Pod] Display name set: {data.decode('utf-8', errors='replace')}")

def key_write(characteristic, data):
    state["private_key"] = data
    print(f"[Pod] Private key set ({len(data)} bytes)")

def amount_write(characteristic, data):
    state["amount_paise"] = data
    if len(data) >= 4:
        amount = (data[0] << 24) | (data[1] << 16) | (data[2] << 8) | data[3]
        print(f"[Pod] Amount set: {amount} paisa (₹{amount/100:.2f})")

def battery_read(characteristic, data):
    return bytes([state["battery"]])


async def main():
    print("=== ESP32 Pod Mock ===")
    print(f"Service UUID: {POD_SERVICE_UUID}")
    print("Characteristics:")
    print(f"  VPA:          {VPA_CHAR_UUID}")
    print(f"  Display Name: {DISPLAY_NAME_CHAR_UUID}")
    print(f"  Private Key:  {PRIVATE_KEY_CHAR_UUID}")
    print(f"  Amount:       {AMOUNT_CHAR_UUID}")
    print(f"  Battery:      {BATTERY_CHAR_UUID}")
    print()
    print("Waiting for connections... (Ctrl+C to stop)")

    vpa_char = BleakGATTCharacteristic(
        VPA_CHAR_UUID,
        ["read", "write"],
        64,
        state["vpa"]
    )
    vpa_char.read = vpa_read
    vpa_char.write = vpa_write

    name_char = BleakGATTCharacteristic(
        DISPLAY_NAME_CHAR_UUID,
        ["read", "write"],
        64,
        state["display_name"]
    )
    name_char.write = name_write

    key_char = BleakGATTCharacteristic(
        PRIVATE_KEY_CHAR_UUID,
        ["write"],
        32,
        state["private_key"]
    )
    key_char.write = key_write

    amount_char = BleakGATTCharacteristic(
        AMOUNT_CHAR_UUID,
        ["read", "write"],
        4,
        state["amount_paise"]
    )
    amount_char.write = amount_write

    battery_char = BleakGATTCharacteristic(
        BATTERY_CHAR_UUID,
        ["read"],
        1,
        bytes([state["battery"]])
    )
    battery_char.read = battery_read

    service = BleakGATTService(POD_SERVICE_UUID, [
        vpa_char,
        name_char,
        key_char,
        amount_char,
        battery_char
    ])

    server = BleakServer([service])

    stop_event = asyncio.Event()

    def signal_handler(sig, frame):
        print("\nShutting down...")
        stop_event.set()

    signal.signal(signal.SIGINT, signal_handler)

    try:
        await server.start()
        print("[Pod] Server started, advertising...")
        await stop_event.wait()
    except Exception as e:
        print(f"[Pod] Server error: {e}")
        print("Note: BleakServer requires bleak >= 0.22.0 with server support")
    finally:
        await server.stop()
        print("[Pod] Stopped.")


if __name__ == "__main__":
    asyncio.run(main())
