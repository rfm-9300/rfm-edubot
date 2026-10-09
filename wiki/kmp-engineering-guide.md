---
updated: 2026-10-09
provenance: agent synthesis from web research (2026-07-03), moved here from Rodrigo's private wiki on 2026-10-09
---

# KMP Engineering Guide — build, test, and ship Kotlin Multiplatform apps

**Audience: any LLM agent (or human) tasked with building a Kotlin Multiplatform mobile app.**
Follow this guide end to end. Where it says MUST, do not deviate without asking the human.
Everything here is agent synthesis from web research (July 2026) — primary sources listed at the
bottom. Ecosystem state: Kotlin Multiplatform is stable and production-proven (Netflix,
McDonald's, Quizlet, Google workloads); Compose Multiplatform for iOS has been **stable since
1.8.0 (May 2025)**.

**Where this binds:** this page is an instruction, not a suggestion, for this repo's
[mobile app](mobile.md) (`mobile/`). It does **not** bind the Ktor JVM server ([overview](overview.md)).
It is the only Kotlin Multiplatform project Rodrigo has, so the guide moved here from his private
wiki on 2026-10-09; a future KMP repo can follow it from here.

---

## 0. TL;DR operating rules for agents

1. **Push logic down.** 80–90% of code lives in `commonMain`. Platform layers are thin rendering /
   integration shells. Every time you write platform code, first ask "can this be common?"
2. **Never improvise the build setup.** Use the structure in §2 and the convention-plugin pattern
   in §3. One-off Gradle hacks in module files are the #1 source of KMP rot.
3. **`expect`/`actual` is a last resort** for *declarations*; prefer interfaces in common code with
   platform implementations injected via DI. Use `expect`/`actual` only for tiny leaf utilities
   (UUID, platform name, file paths).
4. **Test in `commonTest` by default.** A test placed in a platform source set needs a reason.
5. **Every feature is done only when**: `./gradlew allTests` passes, the Android app builds
   (`assembleDebug`), the iOS framework compiles (`linkDebugFrameworkIosSimulatorArm64` or an
   Xcode build), lint/format pass, and the change is exercised once in a running app or UI test.
6. **Fakes over mocks.** Hand-written fakes in `commonTest` beat mocking frameworks (most mocking
   libs are JVM-only; Kotlin/Native hates them).
7. **Don't silently upgrade toolchain versions** mid-task. Version bumps are their own task.

---

## 1. Decide what you're sharing (before any code)

| Strategy | What's shared | When to pick |
|---|---|---|
| **Shared logic + native UI** | data, domain, ViewModels/presenters in Kotlin; SwiftUI + Jetpack Compose UIs | Existing native apps, iOS-feel-critical products, teams with Swift expertise |
| **Shared everything (CMP)** | all of the above **plus** Compose Multiplatform UI on both platforms | New apps, small teams, speed over per-platform polish. Default for greenfield in 2026 |
| **Incremental adoption** | one module/feature at a time into an existing app | Brownfield. Start with a leaf feature or the network/db layer, ship, expand |

Compose Multiplatform on iOS is stable with native-feeling scroll physics, iOS text selection,
drag-and-drop, and accessibility support. The remaining asterisks: heavy platform-widget
integration (maps, web views, camera) means `UIKitView` interop work, and app binary is larger
than pure SwiftUI. If the human hasn't specified, ask once; default to **shared everything** for
new apps.

---

## 2. Project structure (2026 default)

JetBrains changed the default template in 2026: the old `composeApp` God-module is gone. The
`shared` module is a pure KMP *library*; each platform gets its own thin app module. AGP 9.0
**no longer allows** applying the Android application plugin inside a multiplatform module, so
this structure is mandatory going forward.

```
my-app/
├── gradle/libs.versions.toml        # version catalog — single source of truth for versions
├── build-logic/                     # convention plugins (§3)
│   └── convention/src/main/kotlin/…
├── shared/                          # KMP library: ALL shared code
│   └── src/
│       ├── commonMain/kotlin/       # the brain: domain, data, ViewModels, (CMP UI)
│       ├── commonTest/kotlin/       # the default home of all tests
│       ├── androidMain/kotlin/      # actuals + Android-only impls
│       ├── iosMain/kotlin/          # actuals + iOS-only impls
│       └── androidUnitTest/ | iosTest/   # platform-specific tests only
├── androidApp/                      # Android entry point: Application, MainActivity, manifest
├── iosApp/                          # Xcode project: entry point, Info.plist, signing
└── .github/workflows/               # CI (§8)
```

Scaling rules:

- Start with **one `shared` module**. Split into feature modules (`feature:home`, `core:network`,
  `core:database`, `core:designsystem`) only when the module gets big or the team parallelizes.
- With multiple shared modules, iOS needs an **umbrella module** that depends on all of them and
  is the single framework exported to Xcode (iOS cannot consume N separate frameworks sanely).
- Native-UI strategy? Split `shared` into `sharedLogic` and `sharedUI` so native app modules don't
  drag in Compose.
- Generate new projects with the JetBrains wizard (`kmp.jetbrains.com`) — it emits this structure.

**Environment**: JDK 21, latest stable Android Studio (with the Kotlin Multiplatform IDE plugin)
or IntelliJ IDEA 2026.1.2+, Xcode 16+, and run `kdoctor` on macOS to verify the setup before
first build.

---

## 3. Gradle: version catalog + convention plugins

Two non-negotiable mechanisms:

**(a) Version catalog** — every version, library, and plugin lives in
`gradle/libs.versions.toml`. No hardcoded coordinates in build files, ever.

**(b) Convention plugins in `build-logic/`** — shared build configuration is declared once and
applied by ID. Module build files shrink to ~10 lines and stay uniform.

```kotlin
// build-logic/convention/src/main/kotlin/KmpLibraryConventionPlugin.kt (sketch)
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.library")
        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(21)
            androidTarget()
            iosArm64(); iosSimulatorArm64()   // add iosX64 only if you support Intel Macs
            applyDefaultHierarchyTemplate()   // gives you iosMain etc. for free
            compilerOptions { allWarningsAsErrors.set(true) }
        }
    }
}
```

A typical set: `convention.kmp.library`, `convention.kmp.feature` (adds Compose + DI + testing
deps), `convention.android.app`. Each module's `build.gradle.kts` then reads:

```kotlin
plugins { id("convention.kmp.feature") }
kotlin { sourceSets.commonMain.dependencies { implementation(projects.core.network) } }
```

Also enable in `gradle.properties`: configuration cache, build cache, parallel builds
(`org.gradle.caching=true`, `org.gradle.parallel=true`, `org.gradle.configuration-cache=true`).
Kotlin 2.3 made iOS **release** link tasks up to ~40% faster — keep the toolchain current.

---

## 4. The library stack (golden defaults, July 2026)

Pick these unless the human overrides. All are KMP-native and production-proven.

| Concern | Default | Why / notes | Alternative |
|---|---|---|---|
| Async | **kotlinx.coroutines + Flow** | the lingua franca; everything below speaks it | — |
| Serialization | **kotlinx.serialization** | compiler-plugin based, zero reflection | — |
| HTTP | **Ktor Client** | coroutine-first; OkHttp engine on Android, Darwin on iOS, plugins for auth/logging/content-negotiation | Ktorfit (Retrofit-style facade over Ktor) |
| Database | **SQLDelight** | KMP-native since day one; generates type-safe APIs from SQL; great test helpers | Room KMP (official, good for teams migrating Android DAOs; iOS support younger) |
| DI | **Koin** | no code-gen, fast builds, easy iOS bootstrap (`initKoin()` from Swift) | kotlin-inject (+ Anvil-style, compile-time safety) |
| Key-value | **multiplatform-settings** or DataStore KMP | wraps SharedPreferences / NSUserDefaults | — |
| Date/time | **kotlinx-datetime** | — | — |
| ViewModel | **androidx.lifecycle ViewModel (multiplatform)** | official; works in `commonMain` with CMP | Decompose components |
| Navigation | **JetBrains Compose Navigation** (multiplatform androidx.navigation) | official, stable, fine for most apps | **Decompose** for complex apps / shared nav with native UIs; Voyager for quick mid-size apps; Navigation3 still alpha — avoid in prod |
| Images | **Coil 3** | KMP-native | Kamel |
| Logging | **Kermit** (Touchlab) | common-code logging with platform sinks | Napier |
| iOS interop | **SKIE** (Touchlab) | see §6 — non-optional for native-Swift-UI consumers | Swift Export (experimental, watch it) |
| Screenshot tests | **Roborazzi** / Paparazzi | JVM-rendered Compose goldens, no emulator | QuickBird Snappy |
| E2E | **Maestro** | one YAML flow language for both platforms | XCUITest + Espresso (per-platform) |

---

## 5. Architecture inside `commonMain`

**Pattern: MVI / Unidirectional Data Flow.** Both Compose and SwiftUI are declarative; a single
immutable `StateFlow<UiState>` per screen plus an `onIntent(Intent)` entry point is the safest
contract across both. This is the consensus 2026 recommendation.

```
UI (Compose / SwiftUI)  →  Intent  →  ViewModel  →  UseCase (optional)  →  Repository
        ↑                                   │
        └──────── StateFlow<UiState> ◄──────┘
```

Rules:

- **One `UiState` data class per screen**, immutable, with sealed `Intent` and (if needed) a
  one-shot `Effect` channel for navigation/toasts. No platform types in any of them.
- **Repository pattern** over data sources; repositories expose `Flow`s and suspend functions,
  never framework types. Database (SQLDelight) is the source of truth; network refreshes it
  (offline-first by default).
- **Dependency direction**: `ui → presentation → domain ← data`. Domain has zero dependencies on
  Ktor/SQLDelight types.
- **Platform access**: define an interface in common (`interface LocationProvider`), implement in
  `androidMain`/`iosMain`, bind in Koin platform modules. Reserve `expect`/`actual` for one-liners.
- **Concurrency**: inject `CoroutineDispatchers` (a small data class of `io/default/main`) — never
  hardcode `Dispatchers.IO` (it doesn't exist on native; and injection makes tests deterministic).
- Kotlin/Native's new memory manager means **no more `freeze()`** — write normal multithreaded
  coroutine code.

---

## 6. iOS integration & interop

Three ways to hand Kotlin to iOS, in order of preference:

1. **Direct Gradle integration** (default, monorepo): Xcode build phase runs
   `embedAndSignAppleFrameworkForXcode`. The wizard sets this up.
2. **XCFramework + Swift Package**: `./gradlew assembleSharedXCFrameworkRelease` (via the
   `XCFramework(...)` DSL), wrap in a `Package.swift`, distribute. For separate iOS repos/teams.
3. **CocoaPods plugin**: legacy; only if the iOS app already lives on Pods.

**Install SKIE** (Gradle plugin) whenever Swift code calls shared APIs directly. It rewrites the
Obj-C header surface so that:
- `suspend fun` → Swift `async throws` (with proper cancellation),
- `Flow<T>` → `AsyncSequence` (`for await item in repo.items { … }`),
- sealed classes → exhaustive Swift enums with associated values.

Without SKIE, Swift sees `Flow` as an opaque object and suspend functions as callback soup.

**Swift Export** (Kotlin 2.2.20+, improved in 2.3) compiles Kotlin straight to Swift-visible API
with no Obj-C bridge — still experimental; JetBrains targets stable in 2026. Track it, don't ship
on it yet; SKIE is the production answer today.

API-surface hygiene for the exported framework: keep it small and intentional — mark internals
`internal`, expose domain types not implementation types, avoid generics-heavy signatures at the
boundary (Obj-C erases them), and prefer `sealed class` results over thrown exceptions crossing
the bridge.

---

## 7. Testing (the part most projects skip — don't)

**Pyramid** (targets from current KMP guidance): ~80%+ unit coverage of domain/data logic in
`commonTest`; integration tests over DB/network edges; a thin set of UI/E2E flows for critical
journeys only.

### 7.1 Unit tests — `commonTest`, run on every platform

- `kotlin.test` for annotations/assertions (add Kotest assertions if you want expressiveness).
- `kotlinx-coroutines-test`: wrap every async test in `runTest` (virtual time).
- **Turbine** for anything that returns `Flow`/`StateFlow`:

```kotlin
@Test fun `emits loading then content`() = runTest {
    viewModel.state.test {
        assertEquals(UiState.Loading, awaitItem())
        viewModel.onIntent(Intent.Load)
        assertIs<UiState.Content>(awaitItem())
        cancelAndIgnoreRemainingEvents()
    }
}
```

- **Fakes, not mocks**: write `FakeUserRepository : UserRepository` with in-memory state in
  `commonTest`. If a mock is truly needed, Mockative is the KMP-compatible option — but fakes
  first.
- Tests in `commonTest` execute on JVM **and** Kotlin/Native — this catches native-only bugs
  (threading, platform stdlib differences) for free. That's the point; don't hide tests in
  `androidUnitTest`.

### 7.2 Integration tests

- **SQLDelight**: in-memory driver per platform (JdbcSqliteDriver / NativeSqliteDriver) via a
  small `expect fun testDbDriver()` — test real queries, not fakes of them.
- **Ktor MockEngine**: test the full client pipeline (serialization, error mapping, auth plugin)
  against canned responses in `commonTest`.

### 7.3 UI tests

- **compose-ui-test for CMP** (`runComposeUiTest`, semantics-based) runs against desktop/JVM
  fast; use for screen-level behavior.
- **Screenshot/golden tests**: Roborazzi (or Paparazzi) renders composables on the JVM and diffs
  against goldens — no emulator, CI-friendly. Gate the design system and key screens with these.

### 7.4 E2E

- **Maestro**: one YAML flow file drives both the Android app and the iOS app. Keep 5–15 flows
  (login, core journey, purchase). For CMP on iOS set
  `ComposeUIViewController { … }` accessibility sync to `Always` so Maestro can see the tree.

### 7.5 Commands (CI and local "definition of done")

```bash
./gradlew allTests                          # every target's tests
./gradlew :shared:testDebugUnitTest         # fast JVM-only loop while developing
./gradlew :shared:iosSimulatorArm64Test     # native correctness (macOS runner)
./gradlew verifyRoborazziDebug              # screenshot diffs (if configured)
./gradlew :shared:koverHtmlReport           # coverage (Kover)
./gradlew detekt ktlintCheck                # static analysis + format
maestro test .maestro/                      # e2e, on device/simulator
```

---

## 8. CI/CD (GitHub Actions reference design)

Three workflows. Keep PR feedback under ~15 min; deploys are tag-triggered.

**`ci.yml` — every PR/push:**
- `ubuntu-latest` job: checkout → JDK 21 (`actions/setup-java`, temurin) →
  `gradle/actions/setup-gradle` (handles build cache) → `./gradlew detekt ktlintCheck
  :shared:testDebugUnitTest :androidApp:assembleDebug` → upload test reports.
- `macos-latest` job (parallel): select Xcode 16.x → `./gradlew :shared:iosSimulatorArm64Test` →
  build the iOS app with `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp
  -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO`.
- Cache: Gradle via the official action; also cache Kotlin/Native's `~/.konan` (huge win).

**`release-android.yml` — on tag `v*`:**
- Decode base64 keystore from secrets (`ANDROID_KEYSTORE_RELEASE_B64`, alias/passwords as
  separate secrets) → `./gradlew :androidApp:bundleRelease` → upload to Play via
  Gradle Play Publisher (`publishReleaseBundle`) or `r0adkll/upload-google-play`, using a Google
  Cloud service-account JSON (base64 secret). Land in an **internal track**, promote manually.

**`release-ios.yml` — on tag `v*` (macOS runner):**
- Signing via **App Store Connect API key** (`.p8` + issuer id + key id as secrets) and either
  **fastlane match** (team-shared signing repo — the sane default) or a p12 + provisioning
  profile pair stored base64 in secrets.
- `cd iosApp && fastlane release` → fastlane lane: `match(type: "appstore")` /
  install cert → `gym` (build+archive, Release config) → `pilot` (upload to TestFlight).
- Gotcha list: provisioning profile must match bundle ID *and* certificate; base64-encode every
  binary secret (raw file secrets corrupt); pin `XCODE_VERSION`; the first `xcodebuild` on a fresh
  runner is slow — cache `~/.konan` and DerivedData where possible.

**Versioning**: single source of truth — derive `versionName`/`MARKETING_VERSION` from the git
tag, and build number from the CI run number, injected at build time. Never hand-edit versions in
two places.

**Secrets inventory** (GitHub → Settings → Secrets): Android keystore (b64) + alias + 2 passwords
+ Play service-account JSON (b64); iOS ASC API key (b64) + key id + issuer id + (match repo token
| p12 + profile b64s). Nothing signing-related ever committed.

---

## 9. Release checklist (per store submission)

1. `allTests`, screenshot verification, and Maestro suite green on the release commit.
2. R8/ProGuard on for Android release; check the shared module's `consumer-rules.pro` covers
   kotlinx.serialization and Ktor if you hit runtime crashes only in release.
3. iOS: build Release once locally; verify app size delta (CMP adds ~9–15 MB over pure SwiftUI)
   and startup time; run on a physical device — simulator hides Metal/perf issues.
4. Crash reporting wired for **both** platforms with Kotlin symbolication: Crashlytics or Sentry
   KMP + CrashKiOS (Touchlab) so Kotlin stack traces survive on iOS.
5. Tag → CI ships to Play internal track + TestFlight → human promotes after smoke test.

---

## 10. Known sharp edges (read before debugging for an hour)

- **iOS runtime crash, works on Android**: usually serialization/reflection stripped by release
  linking, or a Kotlin exception crossing into Swift uncaught — catch at the boundary, return
  sealed results.
- **`Dispatchers.IO` unresolved in common code**: it's JVM-only; inject dispatchers (§5).
- **Slow iOS builds**: release linking is the cost center; Kotlin 2.3 cut it up to 40% — upgrade,
  cache `~/.konan`, and keep `iosX64` target off unless needed.
- **Mocking library fails on native**: expected; use fakes (§7.1).
- **Two shared frameworks in one Xcode app**: not supported sanely — umbrella module (§2).
- **AGP 9**: Android application plugin can't live in a KMP module anymore; keep app modules
  separate (§2).
- **Amper**: JetBrains' declarative build tool is progressing (0.7.x) and worth watching, but
  Gradle remains the production build system in 2026.

---

## 11. End-to-end agent workflow (the loop to follow)

**New project**: wizard structure (§2) → version catalog + convention plugins (§3) → golden stack
(§4) → CI skeleton (§8) *before* the first feature → then features.

**Every feature, in order:**
1. Define `UiState` / `Intent` / domain models in `commonMain`.
2. Write the repository + use-case with a fake data source; **write `commonTest` tests now**, not
   after (Turbine for flows).
3. Real data source (Ktor/SQLDelight) + MockEngine/in-memory-driver integration tests.
4. ViewModel + tests.
5. UI (Compose in common, or per-platform) + screenshot test for new screens.
6. Wire DI, navigation.
7. Gate: `./gradlew detekt ktlintCheck allTests :androidApp:assembleDebug` + iOS simulator test
   task; run the app once on each platform (or Maestro flow) and exercise the feature.
8. Only then report done — with the command outputs, not the claim.

On a multi-agent harness, steps 2–5 parallelize across workers per feature, with the brain owning
integration and the quality gate. Live instance of this guide: [mobile](mobile.md).

## Open questions / watch list

- **Swift Export** stabilization (replaces SKIE eventually) — re-evaluate on each Kotlin release.
- **Navigation3** maturing past alpha; **Amper** as a Gradle replacement.
- Room KMP vs SQLDelight convergence — Room may become the default once iOS support hardens.

## Sources (web research, 2026-07-03)

- [JetBrains: new default KMP project structure (May 2026)](https://blog.jetbrains.com/kotlin/2026/05/new-kmp-default-structure/)
- [Kotlin docs: recommended project structure](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html) · [project configuration choices](https://kotlinlang.org/docs/multiplatform/multiplatform-project-configuration.html) · [iOS integration methods](https://kotlinlang.org/docs/multiplatform/multiplatform-ios-integration-overview.html) · [Swift package export](https://kotlinlang.org/docs/multiplatform/multiplatform-spm-export.html) · [testing tutorial](https://kotlinlang.org/docs/multiplatform/multiplatform-run-tests.html) · [Gradle best practices](https://kotlinlang.org/docs/gradle-best-practices.html) · [What's new in Kotlin 2.3](https://kotlinlang.org/docs/whatsnew23.html)
- [JetBrains: Compose Multiplatform 1.8.0 — iOS stable](https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/) · [KMP roadmap](https://blog.jetbrains.com/kotlin/2025/08/kmp-roadmap-aug-2025/)
- [commonmain.dev: KMP ultimate guide 2026](https://commonmain.dev/kotlin-multiplatform/)
- [kmpship: CI/CD for KMP (Actions + fastlane + signing)](https://www.kmpship.app/blog/ci-cd-kotlin-multiplatform-2025) · [KMP testing guide](https://www.kmpship.app/blog/kotlin-multiplatform-testing-guide-2025) · [production-ready in 2026?](https://www.kmpship.app/blog/is-kotlin-multiplatform-production-ready-2026)
- [Touchlab SKIE integration guide (carrion.dev)](https://carrion.dev/en/posts/kmp-ios-skie-integration/)
- [MVP Factory: CMP navigation in 2026 — Decompose vs Voyager vs official](https://mvpfactory.io/blog/compose-multiplatform-navigation-in-2026-decompose-vs-voyage/)
- [Maestro: cross-platform UI testing best practices](https://maestro.dev/blog/best-practices-for-cross-platform-maestro-ui-testing-for-android-and-ios)
- [Convention plugins for KMP (creativesoftware.com)](https://www.creativesoftware.com/blog-posts/simplifying-build-logic-in-kotlin-multiplatform-projects-with-gradle-convention-plugins)
