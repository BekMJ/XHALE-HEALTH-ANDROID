# Structure

## Top-level layout
- `app/`: Android application module (entry points, root navigation, onboarding/disclaimer/settings, app config).
- `feature/`: Feature modules.
- `core/`: Reusable platform/shared modules.
- `gradle/`, `gradlew*`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`: build system.
- `Resources/`: non-code design/media assets.
- `.planning/codebase/`: generated architecture/codebase documentation.

## Module directories and key locations
1. App module
- `app/src/main/java/com/xhale/health/XHaleApp.kt`: application entry.
- `app/src/main/java/com/xhale/health/MainActivity.kt`: activity + root `NavHost`.
- `app/src/main/java/com/xhale/health/di/`: app-level DI/config.
- `app/src/main/java/com/xhale/health/prefs/`: DataStore user-flow preferences.
- `app/src/main/java/com/xhale/health/settings/`: settings feature + repository + DI.
- `app/src/main/res/`: app resources.

2. Core modules
- `core/ble/src/main/java/com/xhale/health/core/ble/`: BLE contracts/models/implementation/DI.
- `core/firebase/src/main/java/com/xhale/health/core/firebase/`: auth/firestore/trends repositories + DI.
- `core/ui/src/main/java/com/xhale/health/core/ui/`: shared Compose theme and network abstraction.

3. Feature modules
- `feature/home/src/main/java/com/xhale/health/feature/home/`: home/trends screens + ViewModels.
- `feature/breath/src/main/java/com/xhale/health/feature/breath/`: breath sampling screen, analysis/export logic, ViewModel.
- `feature/auth/src/main/java/com/xhale/health/feature/auth/`: auth UI + ViewModel.

## Naming and organization conventions
- Package convention mirrors module boundaries:
  - `com.xhale.health.core.<domain>`
  - `com.xhale.health.feature.<feature>`
  - `com.xhale.health` and subpackages for app shell.
- File naming convention is type-oriented:
  - `*ViewModel.kt` for presentation state logic.
  - `*Repository.kt` for data/service access.
  - `*Module.kt` for Hilt providers.
  - `*Screen.kt` / `*Route` patterns for Compose UI.
- Build files use Kotlin DSL (`build.gradle.kts`) in every module.
- Source sets follow standard Android layout (`src/main/java`, `src/main/res`, `src/main/AndroidManifest.xml`).

## Dependency declaration locations
- Root plugin versions: `build.gradle.kts`.
- Module registration: `settings.gradle.kts`.
- Per-module dependencies/configuration: each module `build.gradle.kts`.

## Practical navigation map
- Start architecture review at `settings.gradle.kts` to see module graph.
- Move to `app/build.gradle.kts` for assembled dependencies.
- Read `MainActivity.kt` for runtime routing.
- Read `core/*/*Module.kt` and `app/*Module.kt` for DI bindings.
- Read feature `*ViewModel.kt` files for end-to-end data flow.
