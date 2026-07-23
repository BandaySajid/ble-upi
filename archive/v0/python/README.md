# BLE UPI Intent Trigger - Android & Mac Web Portal

A connection-oriented Bluetooth Low Energy (BLE) intent triggering system that allows you to send UPI payment links from a web browser on macOS to launch a local payment app (Google Pay, PhonePe, Paytm, BHIM, etc.) on an Android phone.

* **Android App (Receiver)**: Built in pure Kotlin (<100KB APK target). Receives the UPI link, displays request details, and launches the Android VIEW intent. Sends back execution status (`LAUNCHED` or `ERROR_NO_APP`).
* **Mac Web Portal (Controller)**: Built with Python (`aiohttp` + `bleak`) and a premium HTML/JS glassmorphic dashboard.

---

## 1. Setting up Python on macOS

### Installation

1. Navigate to the `python` directory.
2. Activate your virtual environment:
   ```bash
   source venv/bin/activate
   ```
3. Install the updated dependencies:
   ```bash
   pip3 install -r requirements.txt
   ```

### Running

1. Enable Bluetooth on your Mac.
2. Start the local server:
   ```bash
   python3 upi_server.py
   ```
3. Open a browser and navigate to:
   **[http://localhost:8000](http://localhost:8000)**

---

## 2. Building and Installing the Android App

From the root project directory:

### Compilation
```bash
cd android
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew assembleDebug
```

### Installation
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 3. How to Use

1. Launch the **BLE UPI Receiver** application on your Android phone.
2. Grant Bluetooth permissions if prompted, and ensure Bluetooth is enabled.
3. Your screen will display: **`Status: Advertising: Listening for Mac connection...`**.
4. Open the web interface at **`http://localhost:8000`** on your Mac.
5. Click **Connect Phone** in the web interface.
   * The web indicator will turn green and display your phone's Bluetooth MAC address.
   * The Android screen status will change to **`Status: Mac connected and ready`**.
6. Enter the payment parameters in the web form:
   * **UPI VPA / ID**: Payee's VPA address (e.g. `merchant@okaxis`, `friend@ybl`).
   * **Payee Name**: The billing name (e.g. `John Doe`).
   * **Amount**: Optional transaction value (e.g. `150.00`).
   * **Note**: Optional payment note (e.g. `Dinner bill`).
7. Click **Trigger UPI Intent via BLE**.
8. **Result**:
   * The phone receives the payload, parses it into an incoming request card on screen, and automatically launches the payment app selector (Google Pay, PhonePe, Paytm, etc.).
   * The Mac portal displays a confirmation toast stating **"UPI Intent opened on phone!"** with real-time feedback confirmation.
   * A historical log is kept at the bottom of both screens.
