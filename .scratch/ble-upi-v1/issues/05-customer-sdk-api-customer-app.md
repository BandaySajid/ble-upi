# 05 — Customer SDK public API + Customer App

**What to build:** A payment app developer imports `:protocol-sdk`, calls `BleUpiScanner.start()`, and receives clean domain callbacks (`onMerchantDetected`, `onPaymentRequestReceived`, `onMerchantLost`). The Customer App is a working end-to-end demo: it scans, verifies, caches merchant profiles, displays a payment card, and launches the UPI app on tap — the full "customer walks into a shop" flow working on a physical device.

**Blocked by:** 04 — BLE discovery pipeline (scanner + RSSI + cooldown)

**Status:** completed

- [ ] `BleUpiScanner` interface wires all internal modules (`BleScanner`, `PayloadDecoder`, `CryptoVerifier`, `RssiFilter`, `CooldownManager`) into a single `start(listener)` / `stop()` API
- [ ] `BleUpiListener` delivers: `onMerchantDetected(MerchantProfile)`, `onMerchantLost(merchantId)`, `onPaymentRequestReceived(PaymentRequest)`, `onError(BleUpiError)`
- [ ] SQLite merchant profile cache: on `onMerchantDetected`, query local DB by short hash → if hit, deliver full profile immediately; if miss, parse in-band compact certificate, verify against hardcoded Root CA, save to cache, deliver
- [ ] `UpiIntentBuilder` constructs `upi://pay?pa=<vpa>&pn=<name>&am=<amount>&tr=<nonce>&tn=<note>` URI from a `PaymentRequest`
- [ ] Customer App: single-activity UI with permission flow (Bluetooth + location), status pill ("Scanning" / "Merchant nearby" / "Payment ready"), payment card view showing merchant name, VPA, amount in ₹
- [ ] Customer App: "Pay Now" button fires `ACTION_VIEW` with the constructed UPI intent; "Dismiss" stops the payment card display
- [ ] Customer App: foreground service with persistent notification ("Scanning for nearby merchants") enabling background detection
- [ ] Customer App NEVER advertises and NEVER acts as a GATT server — verify with Bluetooth sniffer or log assertions
- [ ] Instrumented test on physical Android device: Python reference emitter (06) broadcasts known payload → Customer App receives, decodes, verifies, renders payment card → verify UI elements populated correctly
- [ ] Tests pass green with `./gradlew :customer-app:connectedCheck`
