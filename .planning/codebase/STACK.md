# STACK

## Languages
- Kotlin is the primary language across all modules.
- Kotlin DSL (`.kts`) is used for Gradle build scripts.
- XML is used for Android manifests/resources.

## Runtime and Platform
- Android app runtime.
- `minSdk = 24`, `targetSdk = 35`, `compileSdk = 35`.
- Java/Kotlin bytecode target: Java 17 (`sourceCompatibility`, `targetCompatibility`, `jvmTarget = "17"`).

## Build System and Tooling
- Gradle wrapper-based build.
- Android Gradle Plugin: `8.13.0`.
- Kotlin Android + Compose plugin: `2.0.20`.
- KSP: `2.0.20-1.0.25`.
- Hilt Gradle plugin: `2.52`.
- Google Services plugin: `4.4.2` (applied conditionally in `app` module).

## Project Structure (Modules)
- `:app`
- `:core:ble`
- `:core:ui`
- `:core:firebase`
- `:feature:home`
- `:feature:breath`
- `:feature:auth`

## UI and App Architecture Libraries
- Jetpack Compose with Compose BOM `2024.09.02`.
- Material3 (`androidx.compose.material3:material3`) and Material Components (`com.google.android.material:material:1.12.0`).
- Navigation Compose `2.8.2`.
- Lifecycle runtime/viewmodel compose `2.8.6`.
- Hilt DI (`com.google.dagger:hilt-android:2.52`, compiler via KSP).
- Hilt Navigation Compose `1.2.0`.

## Concurrency and State
- Kotlin coroutines `1.9.0` (`core`, `android`, `play-services`).
- Kotlin Flow used in repositories and reactive state.

## Data and Storage
- AndroidX DataStore Preferences `1.1.1` for local key-value app settings/user preferences.
- No Room/SQLite ORM dependency detected.

## Networking and Connectivity
- No Retrofit/OkHttp/Ktor HTTP client stack detected.
- Android connectivity APIs used for network reachability status (`ConnectivityManager`, `NetworkCapabilities`).
- BLE stack implemented using Android Bluetooth/BluetoothGatt APIs.

## Cloud and Backend SDKs
- Firebase BOM `33.7.0`.
- Firebase Authentication KTX.
- Firebase Firestore KTX.

## Visualization / Third-party Utilities
- MPAndroidChart `v3.1.0` (via JitPack repository).

## Repositories and Dependency Sources
- `google()`
- `mavenCentral()`
- `gradlePluginPortal()` (plugin management)
- `https://jitpack.io`

## Build/Config Conventions
- `android.useAndroidX=true`
- `android.nonTransitiveRClass=true`
- `org.gradle.parallel=true`
- `org.gradle.configureondemand=true`
- Firebase/Google services activation is configuration-driven:
  - `com.google.gms.google-services` plugin is applied only when `google-services.json` exists.
  - Runtime Firebase enablement checks `FirebaseOptions.fromResource(context) != null`.

## Android Permissions/Capabilities (tech-relevant)
- Internet/network state permissions.
- BLE-related permissions across API levels (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, legacy Bluetooth/location).
- Foreground service permissions for connected-device/data-sync use cases.
- Notification permission (`POST_NOTIFICATIONS`) and storage permissions for CSV export paths.
