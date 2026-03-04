# CONVENTIONS

## Scope
This document summarizes coding conventions observed in `/Users/npl-weng/Desktop/XHALE-HEALTH-ANDROID` as currently implemented.

## Code Style
- Language/toolchain baseline is Kotlin + Android Gradle Plugin with Java 17 target (`jvmTarget = "17"`) across modules.
- Kotlin style uses 4-space indentation and trailing commas are generally avoided in declarations/calls.
- Common visibility/default style:
  - `private val _state` backing fields with public immutable `StateFlow` exposure (`val state: StateFlow<...>`).
  - Module-level DI uses `object ...Module` with `@Module`, `@InstallIn(SingletonComponent::class)`, and `@Provides` methods.
- UI stack is Compose-first (`buildFeatures { compose = true }` in UI/feature modules).

## Naming Conventions
- Package naming is hierarchical by domain and layer: `com.xhale.health.core.*`, `com.xhale.health.feature.*`.
- Type naming patterns:
  - View models: `*ViewModel` (e.g., `HomeViewModel`, `BreathViewModel`).
  - UI state models: `*UiState` data classes.
  - Repository interfaces/impl: `*Repository` + platform-specific implementation (`AndroidBleRepository`) and fake implementation (`FakeBleRepository`).
  - DI modules: `*Module` (e.g., `BleModule`, `FirebaseModule`).
- Constants are lowerCamelCase `val`/`var` in class scope (e.g., `warmupDelaySeconds`, `baselineCaptureDelaySeconds`), not screaming snake case.

## Architectural Patterns
- Modular feature/core split:
  - `feature/*` modules for screens and view-model orchestration.
  - `core/*` modules for reusable infrastructure (BLE, UI/network wrappers, Firebase).
- Dependency injection via Hilt is standard across modules (`@HiltViewModel`, constructor injection with `@Inject`, DI provider modules).
- Reactive state pattern is consistent:
  - Repositories expose `Flow`/`StateFlow`.
  - ViewModels collect and map streams into immutable UI state.
  - State mutation is performed with `MutableStateFlow.update { it.copy(...) }`.
- Asynchronous work is coroutine-based (`viewModelScope.launch`, repository-scoped coroutine scope in BLE impl).
- Explicit fake adapter exists in BLE layer (`FakeBleRepository`) indicating a seam for simulation/manual testing.

## Error Handling Patterns
- Error signaling is mixed but pragmatic:
  - Repository API methods may return `Result<T>` for operation success/failure (`AuthRepository`).
  - UI-facing errors are often surfaced as message strings in state (`error: String?`, `exportResult: String?`) rather than typed domain errors.
- Exception handling is generally local and defensive:
  - `try/catch` blocks around Firebase calls and init listeners.
  - `runCatching { ... }.getOrNull()` for best-effort parsing in BLE scan decoding.
  - In some locations exceptions are intentionally ignored (`catch (_: Exception)`) to keep app state stable with fallback messaging.
- Guard-clause style is common for preconditions (connectivity/device readiness) with early returns.

## Consistency Notes
- Strong consistency in module naming, ViewModel + UiState structure, and Flow-based state management.
- Error-model consistency is partial: `Result<T>` is used in some repositories while other flows push raw nullable error strings into UI state.
