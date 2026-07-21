import asyncio
import json
import urllib.parse
from aiohttp import web
from bleak import BleakScanner, BleakClient

# Configuration
SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
RX_CHAR_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
TX_CHAR_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

merchant_config = {"merchant_vpa": "mbandaysajid@oksbi", "merchant_name": "Sajid Banday"}

# Track connected devices so we don't connect to the same one twice
# Maps address -> status dictionary
processed_devices = {}
processed_devices_lock = asyncio.Lock()

async def handle_device_connection(device):
    """Connects to a single device, writes the payload, waits for ACK, and disconnects."""
    address = device.address
    async with processed_devices_lock:
        if address in processed_devices:
            return  # Already processing or processed this device recently
        processed_devices[address] = {"status": "connecting", "timestamp": asyncio.get_event_loop().time()}
        
    print(f"\n[+] New device detected: {address} ({device.name})")
    
    # Build payload
    vpa = urllib.parse.quote(merchant_config.get("merchant_vpa", ""))
    name = urllib.parse.quote(merchant_config.get("merchant_name", ""))
    payload = f"upi://pay?pa={vpa}&pn={name}&cu=INR"
    
    client = BleakClient(device)
    try:
        await client.connect(timeout=5.0)
        print(f"[{address}] Connected successfully.")
        
        # We use an event to wait for the ACK from the phone
        ack_event = asyncio.Event()
        
        def notification_handler(sender, data):
            msg = data.decode('utf-8')
            print(f"[{address}] Received: {msg}")
            if msg == "STATUS:NOTIFIED" or msg == "STATUS:LAUNCHED":
                asyncio.create_task(mark_processed(address, msg))
                ack_event.set()
                
        await client.start_notify(TX_CHAR_UUID, notification_handler)
        
        # Write payload
        print(f"[{address}] Writing payload...")
        await client.write_gatt_char(RX_CHAR_UUID, payload.encode('utf-8'), response=False)
        
        # Wait up to 3 seconds for the phone to send STATUS:NOTIFIED
        # Wait up to 3 seconds for the phone to send STATUS:NOTIFIED
        try:
            await asyncio.wait_for(ack_event.wait(), timeout=3.0)
            print(f"[{address}] Payload delivered and acknowledged!")
        except asyncio.TimeoutError:
            print(f"[{address}] Timed out waiting for ACK. Disconnecting anyway.")
            # Do NOT pop from processed_devices on timeout. Let it cool down.
            
    except Exception as e:
        print(f"[{address}] Connection failed: {e}")
        async with processed_devices_lock:
            # Change status so it's not retried for at least 10 seconds
            if address in processed_devices:
                processed_devices[address]["status"] = "failed"
    finally:
        if client.is_connected:
            await client.disconnect()
            print(f"[{address}] Disconnected.")

async def mark_processed(address, status):
    async with processed_devices_lock:
        if address in processed_devices:
            processed_devices[address]["status"] = status

def detection_callback(device, advertisement_data):
    """Fired INSTANTLY when a BLE advertisement is received."""
    if SERVICE_UUID.lower() in [u.lower() for u in advertisement_data.service_uuids]:
        # Fire and forget a connection task
        asyncio.create_task(handle_device_connection(device))

async def cleanup_stale_devices():
    """Periodically remove devices from the processed list so they can be triggered again if needed."""
    while True:
        await asyncio.sleep(10)
        now = asyncio.get_event_loop().time()
        async with processed_devices_lock:
            to_remove = []
            for addr, info in processed_devices.items():
                # Allow re-triggering the same phone after 30 seconds
                if now - info["timestamp"] > 30:
                    to_remove.append(addr)
            for addr in to_remove:
                del processed_devices[addr]

async def start_ble_scanner():
    print("=" * 60)
    print("   BLE UPI TERMINAL — FAST MULTI-DEVICE MODE")
    print("=" * 60)
    
    scanner = BleakScanner(detection_callback=detection_callback)
    await scanner.start()
    print("Continuous scanner started. Waiting for phones...")
    
    # Run background cleanup task
    asyncio.create_task(cleanup_stale_devices())
    
    # Keep running forever
    while True:
        await asyncio.sleep(3600)

if __name__ == "__main__":
    try:
        asyncio.run(start_ble_scanner())
    except KeyboardInterrupt:
        print("Shutting down.")
