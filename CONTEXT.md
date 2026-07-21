# BLE UPI Proximity Terminal — Complete System & Architectural Context

> **Target Audience**: Future AI models, developers, and system architects working on this repository.
> **Purpose**: This document (`CONTEXT.md`) serves as the definitive, exhaustive, zero-omission technical specification, architectural history, design rationale, and execution guide for the **BLE UPI Proximity Terminal** project (`ble-upi`). Read this entire document before proposing modifications or debugging issues.

---

## 1. Executive Summary & Project Overview

The **BLE UPI Proximity Terminal** is an ultra-fast, zero-dependency, connection-oriented Bluetooth Low Energy (BLE) payment broadcasting system. It allows a local merchant terminal (running on macOS with Python) to automatically discover any customer Android phone in physical proximity (within the room or checkout counter), establish an instant BLE connection, transmit a customized UPI (Unified Payments Interface) payment payload (`upi://pay?...`), trigger a high-visibility push notification & on-screen payment request card on the customer's phone, receive an execution acknowledgment, and disconnect within milliseconds.

### Core Problem & Design Objectives
1. **Physical Proximity Payments**: Eliminate the need for customers to open their camera and scan QR codes. The moment a customer with the app approaches the counter or enters the venue, their phone receives the merchant's payment request.
2. **Infinite Scaling & Multi-Device Handling (20+ Devices)**: Standard BLE GATT connections on macOS and Android are hard-capped by hardware/OS limits (typically 7–10 concurrent connections). To handle 20+ customers arriving at staggered intervals without hitting connection saturation or dropping devices, the system uses a **Fast Continuous Scanner / Instant-Drop Protocol**.
3. **Cyber-Fintech Brutalism Aesthetic & Zero-Dependency Footprint**: The Android receiver app (`com.example.blechat`) is engineered in pure Kotlin with raw Android XML views—avoiding heavy UI frameworks like Jetpack Compose or third-party BLE wrapper SDKs. The resulting compiled APK is **~826 KB (<1 MB)**, launching instantly with zero bloat and minimal battery drain.

---

## 2. System Architecture & BLE Topology

### Why GATT Server on Android and GATT Client on macOS?
* **Android (Peripheral / GATT Server)**: Android phones act as BLE Peripherals hosting a custom GATT Server (`BluetoothGattServer`). They continuously advertise a unique Service UUID. By hosting the GATT server on the phone, the phone stays passive until a merchant terminal actively writes to it.
* **macOS (Central / GATT Client)**: The Mac acts as a BLE Central (`BleakScanner` + `BleakClient`). It continuously scans the RF spectrum for advertisements matching our Service UUID. When detected, it connects as a GATT Client, writes the UPI payload to the phone's RX characteristic, listens on the TX characteristic for acknowledgment, and immediately drops the connection.

### Specification & UUID Definitions
All communication occurs over a primary custom GATT Service:
* **Primary Service UUID**: `6e400001-b5a3-f393-e0a9-e50e24dcca9e` (Advertised in BLE packets by Android).
* **RX Characteristic (Write / Write No Response)**: `6e400002-b5a3-f393-e0a9-e50e24dcca9e`
  * **Direction**: Mac Central $\rightarrow$ Android Peripheral.
  * **Payload Format**: UTF-8 encoded UPI URI string.
  * **Example Payload**: `upi://pay?pa=mbandaysajid%40oksbi&pn=Sajid%20Banday&cu=INR`
* **TX Characteristic (Notify / Read)**: `6e400003-b5a3-f393-e0a9-e50e24dcca9e`
  * **Direction**: Android Peripheral $\rightarrow$ Mac Central.
  * **Client Characteristic Configuration Descriptor (CCCD)**: `00002902-0000-1000-8000-00805f9b34fb` (Enabled automatically by Bleak on `start_notify`).
  * **Payloads**: UTF-8 status strings:
    * `"STATUS:NOTIFIED"` — The phone successfully parsed the UPI string, populated the on-screen card, and fired the system push notification.
    * `"STATUS:LAUNCHED"` — The user tapped the notification or "Pay Now" button, launching their installed UPI app (Google Pay, PhonePe, Paytm, etc.).

---

## 3. The Fast Continuous Scanning & Cooldown Protocol (macOS Side)

To support dozens of devices without exhausting the OS BLE connection table (`NSRunLoop`/`CoreBluetooth` limits on Mac), `python/upi_server.py` implements a specialized lifecycle:

1. **Continuous Detection Callback (`detection_callback`)**:
   Instead of running batch scans with sleep cycles, `BleakScanner(detection_callback=detection_callback)` runs indefinitely. Every single BLE advertisement packet detected with `SERVICE_UUID` instantly fires `handle_device_connection(device)` as a non-blocking `asyncio.create_task`.
2. **Lock-Protected Device Registry (`processed_devices`)**:
   An `asyncio.Lock()` ensures two concurrent packets from the same phone do not spawn duplicate connection attempts. `processed_devices[address]` tracks the current state (`connecting`, `STATUS:NOTIFIED`, `failed`).
3. **Instant Payload Delivery & Acknowledgment (`handle_device_connection`)**:
   * Connects to the phone (`client.connect(timeout=5.0)`).
   * Subscribes to `TX_CHAR_UUID` notifications (`client.start_notify(...)`).
   * Writes the UPI payload (`client.write_gatt_char(RX_CHAR_UUID, payload, response=False)`).
   * Waits up to **3.0 seconds** using an `asyncio.Event()` (`ack_event.wait()`) for `"STATUS:NOTIFIED"` or `"STATUS:LAUNCHED"`.
4. **Immediate Disconnection & Slot Reclamation (`finally: await client.disconnect()`)**:
   The very millisecond the acknowledgment arrives (or after the 3s timeout reaches), the Mac **disconnects**. This frees up the RF channel and the CoreBluetooth connection slot so the Mac can immediately service the next customer in the room.
5. **Cooldown & Stale Device Cleanup (`cleanup_stale_devices`)**:
   A background task runs every 10 seconds. If a device completed (`STATUS:NOTIFIED`) and more than **30 seconds** have elapsed since its timestamp, its address is purged from `processed_devices`. This 30-second cooldown prevents the Mac from repeatedly spamming the same customer while they are standing near the counter. If a connection fails (`Exception`), the status is set to `failed` and stays in the registry for at least 10 seconds to prevent tight loop retries.

---

## 4. Android Application Architecture & Lifecycle (`android/`)

The Android application is written in pure Kotlin and split across two core components: `MainActivity.kt` (UI & Lifecycle Management) and `BleUpiService.kt` (Foreground Service & GATT Engine).

### `MainActivity.kt` — Permissions, Native Dialogs & Active Monitoring
* **Permission Cascade**: On startup (`onCreate`), the app checks for runtime permissions:
  * Android 12+ (API 31+): `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`.
  * Android 13+ (API 33+): `POST_NOTIFICATIONS`.
  * Android 11 and below: `ACCESS_FINE_LOCATION`.
* **Native Bluetooth Adapter Enablement Prompt (`checkBluetoothAndStart`)**:
  If permissions are granted, `checkBluetoothAndStart()` checks `BluetoothAdapter.isEnabled`. If disabled, instead of failing silently, it launches the native system intent `BluetoothAdapter.ACTION_REQUEST_ENABLE` (`REQUEST_ENABLE_BT = 102`). When the user taps "Allow", `onActivityResult` catches `Activity.RESULT_OK` and proceeds.
* **Active Foreground Bluetooth State Receiver (`bluetoothStateReceiver`)**:
  If the user is actively inside the app and drags down the quick settings shade to turn Bluetooth **OFF**, the registered `BroadcastReceiver` listening for `BluetoothAdapter.ACTION_STATE_CHANGED` instantly intercepts `EXTRA_STATE == STATE_OFF`, displays a warning, and re-triggers the native enable prompt (`checkBluetoothAndStart()`). The receiver is registered in `onResume()` and unregistered in `onPause()` to prevent memory leaks.
* **System Overlay Permission (`checkOverlayPermissionAndStart`)**:
  Checks `Settings.canDrawOverlays(this)`. If missing, prompts the user via `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` so that background intent triggers can pop open the payment UI when the phone is locked or in another app.
* **UI Status & Log Binding (`BleServiceListener`)**:
  In `onResume()`, `MainActivity` attaches an anonymous listener to `BleUpiService.listener`. When `BleUpiService` updates its status or receives a payment request, `runOnUiThread` updates `tvStatus`, `tvChatLog`, and unhides the `cardPayment` view (`tvPayeeName`, `tvPayeeVPA`, `tvAmount`, `btnPayNow`).

### `BleUpiService.kt` — GATT Server, One-and-Done Advertising & Watchdog
* **Foreground Service**: Runs as a persistent Foreground Service (`startForegroundService` on API 26+) so the OS never kills the GATT server while the phone is in the pocket.
* **Duplicate GATT Service Guard (`setupGattServer`)**:
  To prevent error crashes where `BluetoothGattServer` exposes duplicate characteristics (`Multiple Characteristics with this UUID...`), `setupGattServer()` returns early if `bluetoothGattServer != null`. Furthermore, right before registering the service, it calls `server.clearServices()` to guarantee a clean GATT table.
* **Error Code 3 Handling (`advertiseCallback`)**:
  If `BluetoothLeAdvertiser.startAdvertising(...)` throws or returns `errorCode == 3` (`ADVERTISE_FAILED_ALREADY_STARTED`), our `advertiseCallback` catches `onStartFailure`. Instead of marking the service as broken, it checks `if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED)` and sets `isAdvertising = true` with status `"Advertising: Listening for Mac..."`. Additionally, `startAdvertising()` calls `adv.stopAdvertising(advertiseCallback)` inside a `try-catch` before starting to clear orphaned sessions.
* **One-and-Done Advertising Protocol (`handleIncomingUpi`)**:
  * Crucial design detail: When the Mac connects, Android does **NOT** stop advertising right away (stopping and restarting causes Android to rotate its Resolvable Private Address (RPA/MAC address), which tricks the Mac into thinking it's a new device and causing endless notification loops).
  * Instead, `BleUpiService` keeps advertising until `handleIncomingUpi(upiString)` successfully receives and parses the UPI URL.
  * Once received, it sets `lastPaymentRequest = PaymentRequest(...)`, fires `showPaymentNotification(...)`, notifies the UI, and **immediately calls `stopAdvertising()`**.
  * By stopping advertisements once the payment request is live, the phone goes completely quiet over RF. The Mac cannot detect or reconnect to it while the customer is reviewing the bill.
* **Intelligent Watchdog Timer (`watchdogRunnable`)**:
  Runs every 3.0 seconds via `mainHandler`:
  * If Bluetooth turns off: Tears down the GATT server, stops advertisements, resets `lastPaymentRequest = null`, cancels notifications, and updates status to `"Bluetooth off. Waiting..."`.
  * If Bluetooth turns back on: Rebuilds `setupGattServer()` and `startAdvertising()` after 500ms.
  * If Bluetooth is on and healthy: Checks `if (connectedDevices.isEmpty() && !isAdvertising && lastPaymentRequest == null) startAdvertising()`. Notice the guard `lastPaymentRequest == null`—this ensures the watchdog never re-enables advertising if there is an active, unpaid payment card on screen.
* **Clean Reset (`resetServiceState`)**:
  When the user taps the **Reset Link** button in `MainActivity`:
  * Stops advertisements, clears `connectedDevices` & `subscribedDevices`.
  * Nullifies `lastPaymentRequest = null`, posts `onResetUI()`, and cancels `PAYMENT_NOTIFICATION_ID`.
  * Posts a delayed task (`500ms`) to cleanly call `startAdvertising()`, allowing the Mac to discover the phone again for the next transaction.

---

## 5. Complete Technology Stack & File Inventory

### File Tree
```
ble-upi/
├── .gitignore                    # Root ignore file for venv, APKs, build artifacts, IDE files
├── CONTEXT.md                    # THIS DOCUMENT (Comprehensive architectural & technical specification)
├── android/                      # Android Receiver Project (Pure Kotlin, <1 MB APK)
│   ├── build.gradle.kts          # Root Gradle script
│   ├── settings.gradle.kts       # Gradle settings & repositories
│   ├── gradlew / gradlew.bat     # Gradle wrappers
│   └── app/
│       ├── build.gradle.kts      # App module build config (SDK 34, Min SDK 24, Kotlin 1.9+)
│       └── src/main/
│           ├── AndroidManifest.xml # Permissions, Foreground Service registration, Activity config
│           ├── res/layout/
│           │   └── activity_main.xml # Brutalist dark theme layout (TextViews, Cards, ScrollView)
│           └── java/com/example/blechat/
│               ├── MainActivity.kt   # Activity lifecycle, permission checks, native BT prompt
│               └── BleUpiService.kt  # GATT Server, BLE advertising, Watchdog, UPI parsing
└── python/                       # macOS Central / Controller Project
    ├── requirements.txt          # Python dependencies (bleak, aiohttp)
    ├── merchant_config.json      # Merchant configuration ("merchant_vpa", "merchant_name")
    ├── upi_server.py             # Main continuous scanning terminal server (Bleak + asyncio)
    ├── scan_test.py              # Diagnostic script to verify local BLE advertisements
    ├── index.html                # Optional web dashboard UI
    └── README.md                 # Quick reference manual
```

### Key Libraries & Versions
* **Python**: Python 3.10+ (Tested on Python 3.14 on macOS ARM64).
  * `bleak >= 0.21.0`: Multi-platform BLE client/scanner using `CoreBluetooth` via `pyobjc` on macOS.
  * `aiohttp >= 3.9.0`: Async HTTP server for optional web dashboard integration.
* **Android**:
  * Compile SDK: `34` (Android 14).
  * Min SDK: `24` (Android 7.0 Nougat).
  * Target SDK: `34`.
  * Language: Kotlin `1.9+`.
  * Build Tool: Gradle `8.4+`.

---

## 6. Comprehensive Build, Execution & Verification Guide

### Step 1: Running the macOS Python Terminal Server
Always run the Python server with unbuffered output (`PYTHONUNBUFFERED=1`) inside the virtual environment so log lines flush immediately to the terminal or log files.

```bash
# 1. Navigate to the python directory
cd /Users/thebanday/Programming/ble-upi/python

# 2. Activate the virtual environment
source venv/bin/activate

# 3. Kill any existing instances occupying port 8000 or stuck in background
lsof -ti:8000 | xargs kill -9 2>/dev/null
ps aux | grep upi_server.py | grep -v grep | awk '{print $2}' | xargs kill -9 2>/dev/null

# 4. Run the fast multi-device continuous scanner server unbuffered
PYTHONUNBUFFERED=1 python3 upi_server.py
```
*Expected Output*:
```
============================================================
   BLE UPI TERMINAL — FAST MULTI-DEVICE MODE
============================================================
Continuous scanner started. Waiting for phones...
```

### Step 2: Building & Deploying the Android Application
Make sure your Android device (e.g., CMF by Nothing Phone 1) is connected via USB/ADB (`adb devices -l` should show `device`). Use JDK 17 explicitly if multiple Java versions exist on macOS.

```bash
# 1. Navigate to the android directory
cd /Users/thebanday/Programming/ble-upi/android

# 2. Compile the debug APK using Gradle
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew assembleDebug

# 3. Install the APK onto the connected Android device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 4. Launch the application explicitly via ADB
adb shell am start -n com.example.blechat/.MainActivity
```

### Step 3: End-to-End Verification Flow
1. Open the app on the Android phone.
2. If Bluetooth is off, verify that the system dialog automatically pops up asking to turn on Bluetooth. Tap **Allow**.
3. Verify the status on the Android screen displays:
   `Status: Advertising: Listening for Mac...`
4. Bring the phone near the Mac. Within 1–2 seconds, the `upi_server.py` terminal will output:
   ```
   [+] New device detected: 836D5E20-A7A1-A292-FA7E-2D1E0FE4C9B4 (None)
   [836D5E20-A7A1-A292-FA7E-2D1E0FE4C9B4] Connected successfully.
   [836D5E20-A7A1-A292-FA7E-2D1E0FE4C9B4] Writing payload...
   [836D5E20-A7A1-A292-FA7E-2D1E0FE4C9B4] Received: STATUS:NOTIFIED
   [836D5E20-A7A1-A292-FA7E-2D1E0FE4C9B4] Payload delivered and acknowledged!
   [836D5E20-A7A1-A292-FA7E-2D1E0FE4C9B4] Disconnected.
   ```
5. On the Android phone:
   * A push notification (`"Pay Sajid Banday • Tap to open UPI payment • mbandaysajid@oksbi"`) appears in the system tray.
   * The on-screen payment card (`cardPayment`) populates with the VPA (`mbandaysajid@oksbi`), Payee Name, and the **Pay Now** button.
   * Status updates to: `Status: Connected to Mac terminal` then `Status: Disconnected. Waiting for Mac...` (while the card remains open and advertisements remain stopped).
6. Tap **Reset Link** on the Android app:
   * The card disappears, placeholder text reappears.
   * Advertisements restart cleanly (`Advertising: Listening for Mac...`).
   * If the 30-second cooldown on the Mac has passed, the Mac will instantly discover the phone again and trigger a new payment cycle.

### Step 4: Debugging Commands
If something goes wrong during development, use these exact diagnostic commands:
```bash
# Dump Android logs filtered specifically to our app tag
adb logcat -d -s BLEChat -t 150

# Check if the Android service is actively running and registered
adb shell dumpsys activity services | grep -E "BleUpiService|isAdvertising"

# Test RAW BLE discovery from Mac without connecting (using scan_test.py)
cd /Users/thebanday/Programming/ble-upi/python && source venv/bin/activate && python3 scan_test.py
```

---

## 7. Troubleshooting & Known Edge Cases

1. **`ADVERTISE_FAILED_ALREADY_STARTED` (Error Code 3)**:
   * *Cause*: Race condition where `startAdvertising()` is triggered while an existing BLE advertisement session is active.
   * *Resolution*: Handled gracefully in `advertiseCallback.onStartFailure(errorCode == 3)`. The callback marks `isAdvertising = true` and treats it as success. Furthermore, `startAdvertising()` calls `stopAdvertising()` first.
2. **`Multiple Characteristics with this UUID...` (Bleak Error)**:
   * *Cause*: Android `BluetoothGattServer` opened multiple times without calling `clearServices()`, causing duplicate characteristic handles to be published in the GATT table.
   * *Resolution*: `setupGattServer()` checks `if (bluetoothGattServer != null) return` and executes `server.clearServices()` before registering characteristics.
3. **Endless Notification / Connection Loop**:
   * *Cause*: Stopping and restarting advertisements on connection/disconnection forces Android to generate a new MAC/RPA address every second. The Mac sees the new address, thinks it's a new customer, and connects again in an infinite loop.
   * *Resolution*: Never stop advertisements on `STATE_CONNECTED`. Only stop advertisements when `handleIncomingUpi` actually receives the payment URL. Combined with the 30-second cooldown in `processed_devices` on the Mac, duplicate loops are completely eliminated.
4. **Bluetooth Hardware State Transitions**:
   * *Cause*: User toggling Airplane Mode or disabling Bluetooth from quick settings leaves `bluetoothGattServer` in a dead or orphaned state.
   * *Resolution*: The 3-second watchdog timer (`watchdogRunnable`) detects `!btEnabled && wasBtEnabled`, tears down all sockets, and waits. When Bluetooth turns on (`btEnabled && !wasBtEnabled`), it waits 500ms for hardware stabilization before calling `setupGattServer()` and `startAdvertising()`.
