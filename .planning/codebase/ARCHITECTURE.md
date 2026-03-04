# Architecture

## High-level pattern
- Multi-module Android app using a feature/core split with Gradle modules.
- UI is built with Jetpack Compose.
- Dependency injection is centralized through Hilt (`@HiltAndroidApp`, `@AndroidEntryPoint`, `@HiltViewModel`, `@Module`).
- State management is reactive with Kotlin Flow and `StateFlow` in ViewModels.
- Repository pattern is used for BLE, Firebase/Auth, network status, and local preferences.

## Layers
1. App shell (`:app`)
- Owns process entry points, top-level navigation, onboarding/disclaimer flow, and app-wide configuration.
- Hosts `MainActivity` and the root `NavHost`.

2. Feature modules (`:feature:home`, `:feature:breath`, `:feature:auth`)
- Contain feature UI (Compose screens/routes), feature ViewModels, and feature-scoped coordination logic.
- Depend on core modules for shared data/services.

3. Core modules (`:core:ble`, `:core:firebase`, `:core:ui`)
- `core:ble`: BLE domain contracts and Android BLE implementation.
- `core:firebase`: auth/session/calibration/trends data access.
- `core:ui`: shared UI/theme plus network connectivity abstraction.

4. Data/platform services
- BLE via Android Bluetooth stack (`AndroidBleRepository`).
- Firebase Auth + Firestore via repositories.
- DataStore-backed local preferences/settings.

## Data flow
1. UI to ViewModel
- Compose screens invoke ViewModel actions (`onScanToggle`, `startSampling`, `signIn`, etc.).

2. ViewModel to Repository
- ViewModels call repository APIs and subscribe to repository Flows.
- Example: `HomeViewModel` combines 8 streams (`ble.*`, `network.isConnected`) into `HomeUiState`.

3. Repository to platform/backends
- BLE repository translates commands and GATT callbacks to typed flow state.
- Firebase repositories read/write authentication and breath session data.
- DataStore repositories expose user/settings flows.

4. State back to UI
- ViewModels expose immutable state flows.
- Compose collects state and re-renders screens.

## Key abstractions
- `BleRepository` interface defines BLE behavior contract; `AndroidBleRepository` is concrete implementation.
- `NetworkRepository` interface abstracts connectivity checks.
- Firebase access is wrapped behind `AuthRepository`, `FirestoreRepository`, and `TrendsRepository`.
- App config flag `@Named("firebase_enabled")` gates Firebase-dependent paths at runtime.

## Entry points and composition roots
- Application entry: `app/src/main/java/com/xhale/health/XHaleApp.kt` (`@HiltAndroidApp`).
- Activity entry: `app/src/main/java/com/xhale/health/MainActivity.kt` (`@AndroidEntryPoint`).
- Navigation root: `App()` composable in `MainActivity.kt` with routes for onboarding, disclaimer, auth, home, settings, breath, trends.
- DI composition roots:
  - `app/.../di/AppConfigModule.kt`
  - `core/ble/.../BleModule.kt`
  - `core/firebase/.../FirebaseModule.kt`
  - `core/ui/.../NetworkModule.kt`
  - `app/.../prefs/UserPrefsModule.kt`
  - `app/.../settings/SettingsModule.kt`
  - `feature/breath/.../BreathModule.kt`

## Cross-module dependency shape
- `:app` depends on all core + feature modules.
- Feature modules depend on selected core modules only.
- `:core:firebase` depends on `:core:ble`.
- Core modules do not depend on feature modules.

## Architectural notes
- Clean-ish modular layering is present, but strict domain/data separation is partial (some feature logic is heavy in ViewModels).
- Offline/availability behavior is explicit in UI flows (network gate before scans/sampling, Firebase gate via config module).
