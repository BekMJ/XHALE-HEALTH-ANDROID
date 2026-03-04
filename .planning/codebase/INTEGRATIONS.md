# INTEGRATIONS

## Overview
The codebase integrates with Firebase services (Auth + Firestore) and Android platform services (BLE, network connectivity, foreground services). No explicit third-party REST/GraphQL API integrations or webhook handlers are present.

## External APIs / Services

### Firebase Authentication
- SDK: `com.google.firebase:firebase-auth-ktx` (via Firebase BOM `33.7.0`).
- Used for email/password flows:
  - Sign in (`signInWithEmailAndPassword`)
  - Sign up (`createUserWithEmailAndPassword`)
  - Sign out
  - Password reset (`sendPasswordResetEmail`)
- Integration is guarded by runtime config; disabled when Firebase options are absent.

### Firebase Firestore
- SDK: `com.google.firebase:firebase-firestore-ktx`.
- Primary data operations:
  - Save breath session records.
  - Read per-user breath sessions (current nested path and legacy fallback path).
  - Delete session data from current/legacy structures.
  - Read/prefetch device calibration documents.
- Observed collection/document patterns:
  - `users/{uid}/sensorData/{deviceId}/breaths/*`
  - `users/{uid}/sessions/*` (legacy fallback/cleanup path)
  - `deviceCalibrations/{serialPrefix8}`

### Google Services Configuration
- Build-time activation:
  - Google Services Gradle plugin is applied only when `google-services.json` exists in accepted app paths.
- Runtime activation:
  - Firebase is considered enabled only when `FirebaseOptions.fromResource(context)` returns a full default option set.

## Databases / Storage Integrations

### Cloud Database
- Firestore (managed cloud NoSQL database) is the only external database integration detected.

### Local Storage
- AndroidX DataStore Preferences is used for local app settings/preferences.
- No Room, SQLDelight, Realm, or direct SQLite persistence dependency detected.

## Auth Providers
- Firebase Authentication is the only auth provider detected.
- Implemented provider type in current code: email/password credentials.
- No OAuth social provider integrations (Google/Apple/Facebook/Auth0/etc.) detected in this codebase.

## Webhooks / Event Endpoints
- No inbound webhook endpoints are implemented (Android client app, no server webhook receiver).
- No explicit outbound webhook integrations detected.

## Other External/System Integrations

### Bluetooth Low Energy (BLE)
- Android Bluetooth LE stack integration (`BluetoothManager`, `BluetoothAdapter`, `BluetoothLeScanner`, `BluetoothGatt`).
- Used for scanning, connecting, service discovery, characteristic reads/notifications.

### Android Network Stack
- Connectivity monitoring via `ConnectivityManager` and network callbacks.
- This integration reports network availability and does not implement custom remote API calls.

### Foreground Service / Notifications
- Foreground BLE service declared (`BleForegroundService`) with connected-device/data-sync service types.
- Notification channel usage for BLE sampling status.

## Not Detected (Important Gaps)
- No Retrofit/OkHttp/Ktor-based backend API client.
- No GraphQL/Apollo integration.
- No crash reporting/analytics SDK explicitly integrated in current modules.
- No payment, messaging, or third-party identity SDKs beyond Firebase Auth.
