# KMP Mobile Frontend Plan

> Build a Kotlin Multiplatform mobile application for Android and iOS as an alternative frontend
> to the tenant dashboard at `/app`. The app consumes the tenant-scoped dashboard API; it does not
> replace the Ktor backend or expose operator/backoffice functionality.

## 0. Recommended Direction

Build a greenfield **Kotlin Multiplatform + Compose Multiplatform** application that shares UI,
presentation, domain, networking, persistence, and localization across Android and iOS.

Keep it in this repository under an independent `mobile/` Gradle root:

```text
Whatsapp-bot/
├── src/                     # Existing Ktor backend
├── mobile/                  # Independent KMP project
│   ├── shared/
│   ├── androidApp/
│   ├── iosApp/
│   ├── build-logic/
│   └── gradle/
└── .github/workflows/
```

This keeps backend and mobile contract changes coordinated without forcing the existing JVM
20/Kotlin 2.0.21 server build to immediately adopt the newer KMP toolchain. The mobile build should
use JDK 21 and stable Kotlin/Compose versions pinned when implementation begins.

The application should consume the tenant dashboard API, not the operator backoffice API. Tenant
isolation and module authorization already exist in `DashboardRoutes.kt` and `Modules.kt`.

## 1. Product Boundary

The mobile application is an alternative to `/app`, not `/admin` or `/backoffice`.

### 1.1 Target users

- Tenant administrators.
- Tenant members.
- Operators using short-lived tenant impersonation, if mobile impersonation is later required.

### 1.2 Existing capabilities

| Capability | Existing backend |
|---|---|
| Authentication | `POST /app/auth/login` |
| Tenant identity and modules | `GET /app/api/me` |
| Overview metrics | `GET /app/api/overview` |
| Contacts | `GET /app/api/contacts` |
| Block/unblock contacts | `PATCH /app/api/contacts/{id}/status` |
| Conversations | `GET /app/api/conversations` |
| Message thread | `GET /app/api/conversations/{id}/messages` |
| Operator reply | `POST /app/api/conversations/{id}/messages` |
| CRM clients | `/app/api/crm/clients` |
| Quotes | `/app/api/crm/quotes` |
| Invoices | `/app/api/crm/invoices` |
| Catalog | `/app/api/crm/standard-items` |
| Persona management | `/app/api/persona/*` |
| AI assistant | `/app/api/assistant/*` |
| Website widget | `/app/api/web-widget` |
| Locale | `POST /app/api/settings/locale` |
| Instagram connection | `/app/api/instagram/connect` |

Navigation must be generated from the `modules` returned by `/app/api/me`. Client-side visibility
is a convenience only; the server remains the authorization boundary.

## 2. Scope Strategy

### 2.1 Mobile MVP

Ship the workflows that benefit most from mobile access:

1. Login and session restoration.
2. Dynamic tenant/module shell.
3. Overview.
4. Conversations and thread viewing.
5. Sending operator replies.
6. Contacts and block/unblock.
7. AI assistant threads, messages, confirm, and cancel.
8. Localization, theme, error handling, and cached reads.

### 2.2 Release 1.1

1. Clients.
2. Quotes.
3. Invoices.
4. Catalog.
5. PDF download and native sharing.
6. Persona test chat.

### 2.3 Release 1.2

1. Persona source editing.
2. Persona file uploads.
3. Website widget settings.
4. Instagram OAuth through the system browser and deep links.
5. Push notifications.
6. Real-time conversation updates.

This sequence avoids delaying the mobile value proposition behind complex desktop-oriented forms.

## 3. Backend Preparation

The current API works for a prototype, but several changes should precede production mobile usage.

### 3.1 Establish a versioned contract

Introduce `/app/api/v1/*` while retaining the current `/app/api/*` routes for the shipped browser
dashboard.

Create an OpenAPI document covering:

- Requests and responses.
- Enum values.
- Authentication.
- Error responses.
- Pagination.
- Upload/download content types.
- Nullability.
- Maximum field lengths.

Do not import Mongo or server domain classes into the mobile app. The mobile application should own
API DTOs generated from or validated against the contract.

### 3.2 Add mobile session management

The current login endpoint returns one JWT with no refresh mechanism. Add:

```text
POST /app/auth/login
POST /app/auth/refresh
POST /app/auth/logout
GET  /app/auth/session
```

Recommended model:

- Short-lived access token.
- Rotating refresh token.
- Refresh tokens stored hashed server-side.
- Device/session identifier.
- Session revocation.
- Disabled users rejected on refresh.
- Generic login errors.
- Login rate limiting.

Store tokens in Android Keystore-backed storage and iOS Keychain, never SQLDelight or plain
settings.

### 3.3 Add pagination

Current contacts, conversations, messages, CRM lists, and assistant lists return complete arrays.
Use cursor pagination:

```json
{
  "items": [],
  "nextCursor": "opaque-value",
  "hasMore": true
}
```

Prioritize:

- Conversations ordered by `lastMessageAt`.
- Thread messages ordered by `createdAt`.
- Contacts ordered by `lastSeenAt`.
- Assistant threads ordered by `updatedAt`.
- CRM lists.

### 3.4 Standardize errors

Replace ad hoc `{ "error": "..." }` responses with:

```json
{
  "code": "MESSAGE_DELIVERY_FAILED",
  "message": "Localized-safe fallback message",
  "requestId": "...",
  "fieldErrors": {}
}
```

The mobile app should translate known error codes locally and show the server message only as a
fallback.

### 3.5 Enforce roles server-side

`DashboardContext` exposes the dashboard user, but most current mutations only check module access.
Before mobile settings and CRM writes ship, define server-side permissions for:

- `TENANT_ADMIN`.
- `TENANT_MEMBER`.
- `operator-imp`.

Examples:

- Members may read conversations and send replies.
- Only admins may modify persona, integrations, widget origins, team, or locale.
- Decide whether impersonation is read-only or write-capable.
- Return capabilities from `/me` rather than duplicating role assumptions in clients.

Suggested response addition:

```json
{
  "modules": ["overview", "conversations"],
  "permissions": [
    "conversation.read",
    "conversation.reply",
    "contact.status.update"
  ]
}
```

### 3.6 Plan real-time updates

There is no dashboard real-time endpoint currently.

For the MVP:

- Pull to refresh.
- Refresh on foreground/resume.
- Poll the open thread every 10-15 seconds.
- Pause polling when backgrounded.

After the MVP:

- Add authenticated WebSocket or Server-Sent Events.
- Emit conversation updated, message created, delivery status changed, and assistant action changed.
- Use push notifications only for background alerts, not as the canonical data channel.

### 3.7 Support mobile OAuth

The existing Instagram flow uses a browser popup and `window.postMessage`, which cannot be reused
directly.

Add:

- System-browser authorization.
- HTTPS universal/app link callback.
- PKCE or a server-issued one-time state.
- Deep link back into the app.
- Status endpoint for interrupted flows.

Leave Instagram connection web-only until this is implemented securely.

## 4. KMP Architecture

Use shared-everything Compose Multiplatform, modularized nowinandroid-style with convention
plugins in `build-logic/` (`edubot.kmp.library`, `edubot.kmp.compose.library`).

```text
mobile/
├── build-logic/          # Convention plugins (KMP + Android library + Compose setup)
├── core/
│   ├── model/            # API DTOs, pure kotlinx.serialization (no Ktor/Compose)
│   ├── network/          # DashboardApi + KtorDashboardApi (OkHttp on Android, Darwin on iOS)
│   ├── common/           # TokenStore, VoiceInput, SessionError
│   ├── localization/     # MobileCopy + MobileStrings (en/pt/es)
│   ├── ui/               # BotTheme, BotColor, shared Compose components
│   └── testing/          # FakeDashboardApi, FakeVoiceInput for commonTest
├── feature/
│   ├── auth/             # LoginExperienceScreen
│   ├── overview/         # OverviewScreen
│   ├── inbox/            # InboxScreen + InboxController
│   ├── contacts/         # ContactsScreen + ContactsController
│   ├── assistant/        # AssistantScreen + AssistantController (voice input)
│   ├── crm/              # CrmScreen + CrmController
│   ├── persona/          # PersonaScreen + PersonaController
│   └── settings/         # SettingsScreen + SettingsController
├── shared/               # App shell: DashboardApp, session state machine, root navigation;
│                         # also the iOS framework umbrella (exports all core/feature modules)
├── androidApp/
├── iosApp/
└── gradle/libs.versions.toml
```

Dependency rule: `androidApp`/`iosApp` → `shared` → `feature:*` → `core:*`. Features never
depend on each other or on `shared`; `core` modules only point downward
(`core:localization → core:common`, `core:network → core:model`). Feature screens take the
narrow state they need (token, tenant, strings) instead of the session state machine, so the
shell in `:shared` is the only place that knows about `DashboardSessionState`.

### 4.1 Dependency direction

```text
Compose UI
    ↓
ViewModel / MVI
    ↓
Use cases where orchestration is non-trivial
    ↓
Repository interfaces
    ↓
API + SQLDelight implementations
```

Domain and presentation code must not expose Ktor, SQLDelight, Android, or iOS types.

## 5. Technology Stack

| Concern | Choice |
|---|---|
| UI | Compose Multiplatform |
| State | Immutable `StateFlow<UiState>` and `onIntent()` |
| Navigation | JetBrains Compose Navigation |
| HTTP | Ktor Client, OkHttp engine on Android, Darwin on iOS |
| Serialization | kotlinx.serialization |
| Local database | SQLDelight |
| Dependency injection | Koin |
| Settings | multiplatform-settings |
| Secure credentials | Common interface backed by Android Keystore and iOS Keychain |
| Date/time | kotlinx-datetime |
| Logging | Kermit |
| Images/files | Coil 3 plus platform file picker/share adapters |
| Unit tests | kotlin.test, coroutines-test, Turbine |
| Network tests | Ktor MockEngine |
| UI tests | Compose UI tests and Roborazzi |
| End-to-end | Maestro |
| Static analysis | Detekt and ktlint |
| Crash reporting | Sentry KMP or equivalent with iOS symbolication |

Use interfaces with injected platform implementations. Reserve `expect`/`actual` for small leaf
utilities.

## 6. Application State

Use a root session state machine:

```kotlin
sealed interface AppState {
    data object RestoringSession : AppState
    data object SignedOut : AppState
    data class SignedIn(
        val tenant: TenantSummary,
        val user: DashboardUser?,
        val modules: Set<DashboardModule>,
        val permissions: Set<Permission>,
    ) : AppState
}
```

Each screen follows MVI:

```kotlin
data class ConversationsUiState(
    val items: List<ConversationItem> = emptyList(),
    val selectedChannel: ChannelAsset? = null,
    val query: String = "",
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: UiError? = null,
)

sealed interface ConversationsIntent {
    data object Load : ConversationsIntent
    data object Refresh : ConversationsIntent
    data class SearchChanged(val value: String) : ConversationsIntent
    data class ChannelSelected(val id: String) : ConversationsIntent
    data class ConversationSelected(val id: String) : ConversationsIntent
    data object LoadMore : ConversationsIntent
}
```

Use effects only for one-time platform actions such as navigation, opening a URL, sharing a PDF,
or showing a transient message.

## 7. Data Strategy

### 7.1 Offline behavior

Use an offline-readable, online-write model:

- SQLDelight stores tenant identity, contacts, conversations, messages, CRM lists, and assistant
  threads.
- Screens display cached content immediately and then refresh.
- Show last-updated state when offline.
- Do not automatically queue operator messages or CRM writes.
- Failed writes remain visible as retryable UI state but require explicit user retry.
- Never replay assistant confirmation or invoice creation automatically because duplicate execution
  has business consequences.

### 7.2 Repository shape

```kotlin
interface ConversationRepository {
    fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>>
    fun observeMessages(conversationId: String): Flow<List<Message>>
    suspend fun refreshConversations(cursor: String? = null): Page<Conversation>
    suspend fun refreshMessages(conversationId: String, cursor: String? = null): Page<Message>
    suspend fun sendMessage(
        conversationId: String,
        assetExternalId: String,
        text: String,
        idempotencyKey: String,
    ): Message
}
```

Add idempotency keys to write endpoints before introducing retries.

## 8. Navigation And UX

### 8.1 Phone navigation

Use four primary destinations:

1. Overview.
2. Inbox.
3. Assistant, only when enabled.
4. More.

`More` contains:

- Contacts.
- Clients.
- Quotes.
- Invoices.
- Catalog.
- Persona.
- Settings.

Only enabled modules appear.

### 8.2 Tablet navigation

Use an adaptive navigation rail and list-detail layouts:

- Conversation list and thread side by side.
- Assistant thread list and chat side by side.
- CRM list and detail/editor side by side.

### 8.3 Visual language

Recreate the existing thebots.lab design language natively rather than translating CSS:

- Dark-first `#0b0d0f`.
- Yellow `#ffd60a` accent.
- High-contrast status colors.
- Geist-equivalent bundled sans font where licensing permits.
- JetBrains Mono for identifiers, money, and technical values.
- Hairline borders instead of excessive shadows.
- Android and iOS system insets.
- Dynamic type/font scaling.
- Minimum 48 dp touch targets.
- Screen-reader semantics and meaningful action labels.
- Respect reduced-motion settings.

### 8.4 Localization

Ship English, Portuguese, and Spanish from the start, matching the existing browser catalogs.

Use Compose resources or a typed localization layer. Do not put user-facing strings directly in
composables.

Locale resolution:

1. Explicit in-app choice.
2. Tenant default from `/me`.
3. Device locale.
4. English fallback.

Persist an explicit choice locally and update `/app/api/settings/locale` only when the user has
permission to change the tenant default. A personal language choice and tenant-wide default should
eventually be separate concepts.

## 9. Delivery Phases

### Phase 0: Product And Contract Baseline

**Duration:** 1 week

Tasks:

- Freeze MVP scope.
- Inventory every browser dashboard request and response.
- Define the `/app/api/v1` OpenAPI contract.
- Define the permission matrix.
- Decide token lifetimes and refresh behavior.
- Define pagination and error envelopes.
- Record the Android application ID and iOS bundle ID.
- Select minimum OS versions.
- Produce wireframes for phone and tablet.
- Define analytics events and privacy constraints.

Exit criteria:

- API specification reviewed.
- MVP screens and navigation approved.
- No unresolved authentication or role-security decisions.
- Backend/mobile contract test approach selected.

### Phase 1: Project Foundation

**Duration:** 1 week

Tasks:

- Generate the modern KMP structure.
- Add a version catalog and convention plugins.
- Configure Android and iOS application modules.
- Add Compose, Ktor, serialization, SQLDelight, Koin, navigation, logging, and testing.
- Add CI for Linux and macOS.
- Establish debug, staging, and production environments.
- Implement design tokens, typography, spacing, icons, and basic components.
- Add English, Portuguese, and Spanish resource catalogs.
- Configure app links/deep links even if OAuth arrives later.

Exit criteria:

- Empty shell runs on an Android emulator and iOS simulator.
- `allTests`, lint, Android debug build, and iOS simulator build pass.
- Dark/light theme and language switching work.
- CI finishes in a practical feedback window.

### Phase 2: Networking, Authentication, And Shell

**Duration:** 1-2 weeks

Tasks:

- Build the typed Ktor API client.
- Add authorization and refresh interceptors.
- Add structured error mapping.
- Implement secure credential storage.
- Implement login, session restoration, logout, and revoked-session handling.
- Load `/me`.
- Map module strings to known modules while safely ignoring unknown future values.
- Build adaptive root navigation.
- Add connectivity awareness and global session-expired handling.
- Add request IDs to logs without logging credentials or message bodies.

Exit criteria:

- A user can log in and relaunch without re-entering credentials.
- Expired access tokens refresh once without duplicate requests.
- An invalid refresh sends the user to login.
- Disabled users cannot restore a session.
- Navigation exactly reflects `/me.modules`.
- Tokens never appear in logs or the local database.

### Phase 3: Overview And Contacts

**Duration:** 1 week

Tasks:

- Build overview cards and cached metrics.
- Add pull to refresh.
- Build the paginated contact list.
- Add search with Flow debounce.
- Build contact details.
- Add block/unblock confirmation and optimistic state with rollback.
- Handle channel badges and localized status labels.

Exit criteria:

- Cached data appears offline.
- Refresh updates without replacing the screen with a spinner.
- Contact mutation failures restore the previous state.
- Cross-tenant IDs continue returning no data or 404 in backend tests.

### Phase 4: Inbox And Operator Replies

**Duration:** 2 weeks

Tasks:

- Build the channel asset picker from `/me.tenant.channels`.
- Build the paginated conversation list.
- Build the message thread with reverse pagination.
- Add foreground polling.
- Add message composer, validation, send state, and retry.
- Disable replies for website conversations.
- Ensure the selected asset matches the conversation channel.
- Handle delivery errors and duplicate taps.
- Add the tablet list-detail layout.

Exit criteria:

- A thread opens from conversations and contacts.
- Messages render in chronological order.
- Sending uses an idempotency key.
- Duplicate taps cannot send duplicate messages.
- Polling stops when the app backgrounds.
- Web conversations clearly explain why reply is unavailable.

### Phase 5: AI Assistant

**Duration:** 1-2 weeks

Tasks:

- List and create assistant threads.
- Display assistant messages.
- Send prompts.
- Render pending actions as typed confirmation cards.
- Show important arguments before confirmation.
- Confirm or cancel once.
- Refresh thread state after action execution.
- Handle conflict when another device already decided the action.
- Prevent hidden automatic resubmission.

Exit criteria:

- Pending actions cannot execute without explicit confirmation.
- The confirmation button becomes unavailable immediately after tapping.
- `409 Conflict` resolves through a thread refresh.
- Unknown future tool actions render safely as generic structured data.
- Assistant state remains scoped to the current dashboard user.

### Phase 6: CRM

**Duration:** 2-3 weeks

Tasks:

- Clients list and creation.
- Catalog list, creation, editing, and deletion.
- Quote list and creation with a line-item editor.
- Invoice list and creation.
- Mark invoice paid.
- PDF download, caching, preview, and native share sheet.
- Format currency and dates from tenant configuration rather than hardcoded `pt-PT`/EUR
  assumptions.
- Add confirmation around destructive and financial mutations.

Exit criteria:

- CRM destinations disappear when their modules are disabled.
- The server rejects disabled-module requests even if a deep link is used.
- Totals match server-calculated values.
- PDF download handles expired authentication and interrupted transfers.
- Financial writes use idempotency keys.

### Phase 7: Persona And Settings

**Duration:** 2 weeks

Tasks:

- Persona state and compiled instructions.
- Add text sources.
- Import PDF, TXT, and Markdown using platform document pickers.
- Delete sources and request rebuild.
- Poll compilation state while foregrounded.
- Persona test chat.
- Channel connection summary.
- Website widget key and allowed-origin editing.
- Copy/share embed snippet.
- Mobile Instagram OAuth after deep-link backend support exists.
- Role-based settings visibility.

Exit criteria:

- Large uploads provide progress and cancellation.
- Unsupported files fail before upload when possible.
- Compilation polling stops after completion or backgrounding.
- Members cannot access administrator mutations through the UI or API.
- OAuth survives app switching and interrupted callbacks.

### Phase 8: Hardening And Beta

**Duration:** 2 weeks

Tasks:

- Accessibility audit.
- Performance profiling on low-end Android and physical iPhone hardware.
- Crash reporting and release symbolication.
- Privacy-safe telemetry.
- Network resilience tests.
- Security review of token handling and deep links.
- Screenshot tests for themes and locales.
- Maestro critical journeys.
- Internal Play track and TestFlight release.
- Pilot with one or two tenants.
- Establish support, rollback, and incident procedures.

Exit criteria:

- No critical accessibility findings.
- No secrets or sensitive conversation text in telemetry.
- Cold start and list scrolling meet agreed budgets.
- The crash-free beta threshold is achieved.
- Critical Maestro flows pass on both platforms.
- Pilot tenants approve the core workflows.

## 10. Testing Plan

### 10.1 Common unit tests

Target domain, repositories, state machines, and ViewModels:

- Login and refresh races.
- Module and permission mapping.
- Pagination merging.
- Cached-then-network emissions.
- Search debounce.
- Optimistic mutation rollback.
- Duplicate-send prevention.
- Assistant confirmation conflicts.
- Date, money, and locale formatting.
- Unknown enum and module handling.

Use handwritten fakes and `kotlinx-coroutines-test`; use Turbine for `Flow`.

### 10.2 API integration tests

Use Ktor MockEngine to test:

- Serialization of every DTO.
- Authorization header injection.
- One refresh for concurrent 401 responses.
- Error-envelope mapping.
- Pagination cursors.
- Multipart persona upload.
- PDF binary download.
- Timeout and retry policy.
- Idempotency headers.

### 10.3 Database tests

Use in-memory SQLDelight drivers to verify:

- Tenant-scoped cache keys.
- Atomic page replacement.
- Message ordering.
- Logout cache cleanup.
- Schema migrations.

Never allow records from one tenant session to appear in another. Either key every cache table by
tenant ID or wipe tenant data on a tenant transition.

### 10.4 UI tests

Cover:

- Login validation.
- Dynamic navigation.
- Empty, loading, error, and content states.
- Contact status confirmation.
- Conversation composer.
- Assistant action card.
- Quote line-item editor.
- Locale and theme changes.
- Accessibility semantics.

### 10.5 End-to-end flows

Keep Maestro focused:

1. Login and restore session.
2. Open a conversation and send a reply.
3. Block and unblock a contact.
4. Create an assistant thread and confirm an action.
5. Create a client and quote.
6. Download/share a PDF.
7. Change locale.
8. Recover from an expired session.

## 11. CI/CD

### 11.1 Pull-request CI

Linux job:

```bash
./gradlew detekt ktlintCheck \
  :shared:testDebugUnitTest \
  :androidApp:assembleDebug
```

macOS job:

```bash
./gradlew :shared:iosSimulatorArm64Test
xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -sdk iphonesimulator \
  -configuration Debug \
  CODE_SIGNING_ALLOWED=NO
```

Also run backend contract tests whenever dashboard routes or OpenAPI files change.

### 11.2 Releases

Use tags as the single version source:

- Android bundle to the Play internal track.
- iOS archive to TestFlight.
- Build number from the CI run.
- Manual promotion after smoke testing.
- Separate staging and production API base URLs.
- No production credentials committed to the repository.

## 12. Security Checklist

- Store access and refresh tokens only in Keystore/Keychain.
- Do not log tokens, passwords, message contents, persona contents, or CRM documents.
- Validate universal/app links strictly.
- Treat module visibility as UI only.
- Enforce permissions and tenant scope on every server route.
- Revalidate the active user during refresh.
- Add idempotency to message and business-data writes.
- Clear sensitive caches on logout.
- Avoid certificate pinning initially unless there is an operational rotation plan.
- Add screenshot obfuscation only if product requirements justify the UX cost.
- Threat-model impersonation separately before supporting it on mobile.

## 13. Key Risks

| Risk | Mitigation |
|---|---|
| Current JWT has no refresh/revocation flow | Complete backend session work before beta |
| Unpaginated APIs degrade with real tenants | Add cursor pagination before the Inbox/CRM release |
| Role checks are incomplete | Define and enforce a permission matrix server-side |
| Browser OAuth cannot work natively | Use the system browser, app links, and one-time state |
| No real-time dashboard feed | Poll in the MVP; add WebSocket/SSE later |
| Duplicate mobile retries cause writes twice | Use idempotency keys and explicit retries |
| Desktop CRM forms are cumbersome on phones | Stage CRM after core mobile workflows |
| API DTOs are private implementation details | Add an OpenAPI contract and compatibility tests |
| Tenant cache leakage | Use tenant-keyed tables plus logout/session-switch cleanup |
| KMP and server toolchains diverge | Keep an independent `mobile/` Gradle root |

## 14. Suggested Timeline

For one experienced KMP engineer with backend support:

| Milestone | Approximate elapsed time |
|---|---:|
| Contract and foundation | 2 weeks |
| Auth, shell, overview, and contacts | 4 weeks |
| Inbox and operator replies | 6 weeks |
| AI assistant MVP | 7-8 weeks |
| Internal Android/iOS MVP | 8 weeks |
| CRM release | 10-11 weeks |
| Persona, integrations, and hardening | 12-14 weeks |

The recommended first production milestone is an eight-week mobile operations app containing
login, overview, inbox, replies, contacts, and the AI assistant. CRM, persona authoring, and
integration setup can follow without blocking the highest-value mobile use cases.

## 15. Definition Of Done

Every feature is complete only when:

1. Domain, repository, and ViewModel behavior is covered in `commonTest`.
2. Network serialization and error handling are covered with Ktor MockEngine.
3. Relevant cached-data behavior is covered with an in-memory SQLDelight driver.
4. The screen has content, empty, loading, offline, and error states.
5. User-facing strings exist in English, Portuguese, and Spanish catalogs.
6. Accessibility semantics and minimum touch targets are verified.
7. `detekt`, `ktlintCheck`, `allTests`, and Android debug assembly pass.
8. iOS simulator tests and the Xcode simulator build pass.
9. The flow is exercised once on both platforms or through the corresponding Maestro flow.
10. No credentials or sensitive tenant data appear in logs, analytics, or crash metadata.
