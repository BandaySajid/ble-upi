import asyncio
import urllib.parse
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

# Guard against scheduling duplicate connection tasks for same address
_pending_connections = set()
_pending_lock = asyncio.Lock()

async def _try_connect_device(device):
    """Deduplicate by address so we don't hammer the same device."""
    addr = device.address
    async with _pending_lock:
        if addr in _pending_connections:
            return
        _pending_connections.add(addr)
    try:
        await handle_device_connection(device)
    finally:
        async with _pending_lock:
            _pending_connections.discard(addr)

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
    print("Continuous scanner started. Waiting for phones...")

    # Run background cleanup task
    asyncio.create_task(cleanup_stale_devices())

    # Keep running forever — periodic discovery loop
    while True:
        try:
            devices = await BleakScanner.discover(timeout=2.0, return_adv=True)
            for address, (device, adv) in devices.items():
                uuids = adv.service_uuids or []
                if SERVICE_UUID.lower() in [u.lower() for u in uuids]:
                    asyncio.create_task(_try_connect_device(device))
        except Exception as e:
            print(f"[SCAN] Error: {e}", flush=True)
        await asyncio.sleep(0.5)  # small gap between scans

if __name__ == "__main__":
    try:
        asyncio.run(start_ble_scanner())
    except KeyboardInterrupt:
        print("Shutting down.")
