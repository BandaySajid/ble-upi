import asyncio
from bleak import BleakScanner

SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"

async def main():
    print("Scanning for our service...")
    devices_dict = await BleakScanner.discover(timeout=5.0, return_adv=True)
    for address, (d, adv) in devices_dict.items():
        if SERVICE_UUID in adv.service_uuids:
            print(f"Found! Address: {address}")
            print(f"Device Name: {d.name}")
            print(f"Adv Local Name: {adv.local_name}")
            print(f"Adv UUIDs: {adv.service_uuids}")
            
asyncio.run(main())
