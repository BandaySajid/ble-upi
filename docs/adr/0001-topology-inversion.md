# Invert BLE topology: merchant broadcasts, customer passively scans

**Status**: accepted

The v0 prototype had the customer's phone advertise as a GATT Server and the merchant terminal connect as a GATT Client. For production, this is inverted: the **Merchant broadcasts** (beacon or GATT Server fallback) and the **Customer passively scans** (scanner + GATT Client in escalation mode only).

## Why invert

1. **Privacy**: If millions of customer phones broadcast a persistent BLE signal, their movements can be tracked across retail stores via MAC/payload fingerprinting. A passive scanner emits nothing.
2. **iOS background limits**: Apple aggressively kills background BLE advertising. Passive scanning survives background execution reliably.
3. **Battery**: Transmitting radio consumes far more power than listening.
4. **Concurrency at scale**: A single merchant beacon broadcasting to N customers scales infinitely (broadcast is one-to-many). In the old topology, the merchant had to establish and tear down N individual GATT connections — hitting OS connection caps.

## Consequences

- Customer app becomes strictly passive: `BluetoothLeScanner` + optional `connectGatt()` for GATT escalation.
- Merchant side takes on all broadcast/server responsibility (ESP32 hardware pod or Virtual Terminal phone mode).
- Current codebase (`android/BleUpiService.kt` GATT Server, `python/upi_server.py` GATT Client) must be rewritten for the new topology.
