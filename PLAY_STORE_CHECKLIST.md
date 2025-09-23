# XHale Health – Google Play checklist

- Target SDK 34, min SDK 24; 64-bit only (default).
- Data safety form: declare BLE usage (device connectivity), no location (BLUETOOTH_SCAN with neverForLocation), notifications, analytics/crash if added.
- Privacy policy URL required (hosted), referenced in Play Console and in-app.
- Runtime permissions:
  - Android 12+: BLUETOOTH_SCAN (neverForLocation), BLUETOOTH_CONNECT.
  - Android 13+: POST_NOTIFICATIONS.
  - Android 14+: Foreground Service types and permissions for CONNECTED_DEVICE, DATA_SYNC.
- Foreground service usage: only while actively scanning/connected; persistent low-importance notification.
- Background execution limits: avoid long scans in background; use user-initiated actions.
- App content: medical disclaimer and non-medical claims; contact email and support URL.
- Store listing: screenshots (light/dark), short/long description, category: Health & Fitness or Medical (non-diagnostic).
- Signing & release: Play App Signing enabled; internal testing track; versioning mirrors iOS where possible.

