# Agent Rules — TeleNebula (Kotlin + Jetpack Compose)

These rules are **mandatory**. Do not deviate without explicit user approval.

The app is a pure Kotlin 2.4 / Jetpack Compose Android app, messaging core included. There is no
Rust, no JavaScript, no React Native, no Expo anywhere in the tree, and no generated bindings.
Versions live in `gradle/libs.versions.toml`; check them before assuming an API exists.

---

## 1. Architectural Layers

Four UI layers with strict responsibilities. Violating a layer boundary is a bug.

### Fragments (`app/…/ui/fragments`)

- Stateless composables: parameters in, lambdas out. An optional `modifier: Modifier = Modifier`
  comes first among the optional parameters.
- **May** contain small local logic (formatting, conditional rendering) and UI-only `remember`
  (scroll state, animation state, a mapped option list keyed on its input).
- **Must not** read a store, a ViewModel, `LocalAppGraph`, or perform I/O.
- Small and reusable. When one grows domain-specific, split it.

### Root components (`app/…/ui/root`)

- The shell: `RootShell`, `TabBar`, `NoticeHost`, `SheetHost`, `AppLockGate`, `CallBanner`,
  `CallOverlay`, `NebulaAdvancedSettings`, the message sheets.
- Read their data from the app-wide singletons through `LocalAppGraph` and forward calls to them.
- No business logic: no data transforms beyond what a `when` over a state needs to render.

### Screens (`app/…/ui/screens/<name>/`)

- `<Name>Screen(viewModel)` collects the ViewModel's single `uiState` **once** and composes
  fragments. Screen-private composables take plain data and lambdas, never the ViewModel.
- No logic, no `mutableStateOf`, no I/O, no `LaunchedEffect` except forwarding a one-shot signal
  (e.g. scroll-to-bottom) to a fragment.

### ViewModels (`<Name>ViewModel.kt`, same folder)

- Own **all** logic: derivations, event handlers, coroutines, navigation calls, notices.
- Expose exactly one `StateFlow<UiState>` built with `combine`/`map` + `stateIn(WhileSubscribed(5_000))`
  plus plain functions for actions. Derived values are computed in the flow, never duplicated in state.
- Constructed inline in `RootShell` with `viewModel { … }` from the `AppGraph` singletons; no DI framework.

### Stores / singletons (`AppGraph`)

- App-wide state and shared logic only: runtime, prefs, identity, notices, sheets, call engine,
  presence, the nebula draft, transfer/typing stores. Created once in `AppGraph`, never elsewhere.
- Screen-specific state never goes here.

---

## 2. Project Layout

```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml   Gradle 9 / AGP 9 / Kotlin 2.4 (built-in Kotlin)
app/      com.telenebula.app      the application: AppGraph, MainActivity, runtime/, nav/, platform/,
                                  notices/, sheets/, nebula/, ui/{theme,icons,fragments,root,screens,shared}
core/     com.telenebula.core     the messaging domain (see §8): db/ (SQLite store), protocol/ (wire frames),
                                  engine/ (transport, inbound dispatch, outbox, transfers, events),
                                  nebula/ (config schema, draft, site render), backup/ (tar),
                                  TnCore + CoreClient (reactive flows), models, notifications, TnCoreService
vpn/      com.telenebula.vpn      NebulaVpnService over the mobile_nebula gomobile AAR (vpn/local-maven), controller
calls/    com.telenebula.calls    WebRTC call engine (org.webrtc via stream-webrtc-android), CallStyle
                                  notifications, foreground service, floating call window; the remote
                                  seat (a Dex browser holding the call's media) behind RemoteSeatPort
dex/      com.telenebula.dex      Dex: the HTTPS + WebSocket server the phone runs for the web frontend
                                  (http/, tls/ over the Android Keystore, auth/), the TURN relay (turn/)
                                  that carries browser media onto the overlay, the wire/ frames, Limits
web/      com.telenebula.web      the Dex frontend: Kotlin/JS, plain DOM, bundled into app assets/dex/
scripts/  build-nebula-aar.sh, gen-icons.py, gen-emoji-catalog.py
```

Rules:

- `:core`, `:vpn`, `:calls`, `:dex` are Android libraries with **no `res/`**: icons and tones they need
  are injected by `:app` (`CoreNotificationConfig`, `CallsConfig`, `DexAssets`) or live in `assets/`.
- `:app` depends on the four libraries; the libraries never depend on `:app` or on each other.
  The call engine talks to the core through the `CoreSignaling` interface that `:app` adapts; the
  Dex server talks to the phone through `DexBackend` and to the call engine through `RemoteSeatPort`,
  both adapted in `app/…/runtime/Dex*.kt`. `:dex` depends on coroutines, serialization and the
  framework only, like `:core`; every bound it enforces lives in `dex/…/Limits.kt` with its reason.
  Its TLS is the one part no unit test can reach, since the key lives in the Android Keystore and
  the restrictions put on it are only refused when a handshake is attempted: `:dex` therefore has
  `src/androidTest` (`./gradlew :dex:connectedDebugAndroidTest`, adding androidx.test as `:core`
  does), and a change to `DexTls` has to run there.
- `:web` is Kotlin/JS with no npm dependencies: `web.js` plus `index.html`, `app.css` and `favicon.svg`
  are copied into `assets/dex/` by `:app`'s `bundleDexWeb` task at build time and are never committed.
  The browser is a frontend only; everything it shows or does happens on the phone through the wire.
- Generated files are committed and never hand-edited: `app/…/ui/icons/TnIcons.kt`
  (`scripts/gen-icons.py`), `app/src/main/assets/emoji_catalog.json` (`scripts/gen-emoji-catalog.py`).
- Models that cross a real boundary (the wire, disk, a database column, the nebula config) are
  `@Serializable` data classes in `core/…/model`, encoded with the single `CoreJson` instance —
  or with the wire's own `FrameCodec.WireJson`, whose field rules the protocol fixes. The Dex wire
  (`dex/…/wire/DexWire.kt`, `DexJson`) is the one other boundary; `web/…/wire` mirrors it field for field.
- **Inside the process there is no JSON.** Modules exchange Kotlin types; a screen, a view model
  or a store that encodes or parses JSON has invented a boundary that does not exist.
  `ArchitectureTest` enforces it.

---

## 3. Kotlin

- `!!` is banned outside tests. Narrow with `?:`, `?.let`, `when`, or a local `val`.
- Platform failures surface as exceptions caught at the ViewModel or runtime boundary and turned
  into a notice (`NoticeCenter.addError/addWarning`); nothing is swallowed silently, nothing reaches
  `Log` as the only report.
- Coroutines: structured only (`viewModelScope`, the `AppGraph` scope, `withContext`). No
  `GlobalScope`, no `runBlocking` on the main thread, no `Thread`.
- Flows: cold flows for queries, `StateFlow` for state, `SharedFlow(extraBufferCapacity = 1)` for
  signals. Debounce/coalesce at the source (the core already coalesces invalidations to 50 ms).
- Immutable data classes for state; `copy` on update; lists replaced, never mutated in place.
- Naming: composables `PascalCase`, files match their primary declaration, booleans `is/has/can/should`.
- Default to no comments. See §3.1.

### 3.1 Comments

**Write no comment unless the code is wrong without it.** The bar is not "this is interesting" or
"this took thought" — it is: a competent reader of this file would get it *wrong*, and no rename or
restructure fixes that. Almost nothing clears that bar. Rename the thing instead.

**One line. Always.** Not a wrapped sentence across two lines — one line, or nothing. If the reason
does not fit, the reason belongs in the commit message and the comment does not exist.

Only these qualify:

- a rule from outside this file — the wire protocol, SQLite 3.18, an Android API
- an invariant the compiler cannot state — an order that matters, a lock, a hazard
- a workaround, and what forced it

Never:

- what the next line does, or a restatement of the signature
- design narration, trade-offs weighed, alternatives rejected — that is the commit message
- a defence of a UI choice: a glyph, a colour, a wording
- the same reason twice, or one already stated where it is enforced
- a comment on a test that repeats the assertion

KDoc follows the same bar and the same one-line limit. A public declaration whose name and
signature already say it gets no KDoc. The existing multi-paragraph KDoc in `core/` predates this
rule: leave it alone, do not imitate it, and do not add more.

Before committing, reread every comment in the diff and delete the ones that merely make the diff
easier to read.

---

## 4. Compose & State

- Prefer deriving to storing: anything computable from state is computed in the ViewModel's flow
  (or `remember(keys)` for pure UI mappings), never kept as a second source of truth.
- Effects are a last resort. Event handlers, derived state and flow collection come first.
- Lists: `LazyColumn`/`LazyVerticalGrid` with stable `key` (ids, never indices) and `contentType`
  for anything longer than a handful of rows. Short static groups may use `Section { row { } }`.
- Recomposition scope is a design decision: pass primitives and stable classes, hoist lambdas that
  capture only stable references, keep hot paths (message bubbles, timelines) free of allocations.
- Animations: 120 ms fades for screen transitions, `Animatable`/`animateFloatAsState` for gestures,
  nothing decorative. A ticker runs only while its subject is active (call timer during a call).
- I/O never happens in a composable. `rememberLauncherForActivityResult` may live in a fragment but
  only forwards its result to a lambda.
- Accessibility: `Role` on every clickable, `contentDescription` on icon-only controls,
  `semantics { selected }` on tabs and pills; colour is never the only signal.

---

## 5. Platform, Navigation, Feedback, Secrets

- **I/O lives in `app/…/platform/`** (files, prefs, identity, keystore, share intents, update
  checks, haptics) and in the library modules. ViewModels call these; composables never do.
- **Navigation only through `Navigator`** (`push/pop/popTo/replaceAll/switchTab/openChat/openCall/
  dismissToTabRoot`). Routes are the `TnKey` sealed hierarchy in `nav/NavKeys.kt`; `Call` is only
  ever on top and never saved in a tab stack. Entry gating (setup vs tabs) happens in `RootController`.
- **Feedback only through `NoticeCenter`** (`addError`, `addWarning`, `setSuccess`, `setPrompt`,
  `setLoading`/`withLoading`) and `SheetCenter`. No `Toast` outside `AppRuntime`'s
  background-only path, no `AlertDialog`, no `ModalBottomSheet`; every overlay is drawn in-tree so
  notices always sit on top.
- **Secrets**: the nebula host private key lives in the Android Keystore-backed `KeystoreBox` and is
  read only by `IdentityStore`; only `AppRuntime` hands it to the VPN start/reload. No other class
  sees the raw key. The Dex TLS key never leaves the Keystore (`DexTls`); the Dex password is stored
  only as its PBKDF2 hash (`Passwords`), and sessions and TURN credentials live in memory only. The identity profile (`profile.json`) and prefs (`prefs.json`) keep the on-disk
  paths and keys the backup reads and writes.
- **Nebula config** is owned by `core/nebula`: `NebulaConfigRepository` renders the site config,
  converts config ↔ editor draft and validates it, all against the golden fixtures. The config is a
  typed `NebulaAdvancedConfig`, so a partial or older stored profile fills itself from the field
  defaults; the app reads it as data (`listen.port`, `tun.mtu`, the restart-vs-reload comparison)
  and never re-implements a rule.

---

## 6. Design System

- Flat and minimalist: no shadows or elevation, no gradients; hierarchy from surface tone,
  hairlines and spacing. Round shapes use half their own height as radius.
- **Colours are tokens only.** Composables read `TnTheme.colors.<token>` (background, surface,
  surfaceRaised, hairline, text, textMuted, accent, accentSoft, onAccent, success, warning, danger, info,
  bubbleIn, bubbleOut, onBubbleOut, overlay). `Color(0x…)`, `MaterialTheme.colorScheme` and
  `isSystemInDarkTheme` are banned outside `ui/theme`, the call screen and controls (always dark
  over video), and the scrims drawn over media and the camera viewfinder. `ArchitectureTest` enforces it.
- Static scales from `ui/theme/TnScales.kt`: `TnSpace` (4-point), `TnRadius` (8/12/16), `TnRow`
  (56 dp rows, 36 dp icon tiles), `TnType` (six sizes with fixed line heights, weights 400/500).
- **Material3 is a primitive supplier only**: `Text`, `Switch`, `Slider`, `HorizontalDivider`,
  `CircularProgressIndicator`, `Icon`/`IconButton`, and the theme's `MaterialTheme` wrapper. No
  `Scaffold`, `TopAppBar`, `NavigationBar`, `Card`, `Button`, `AlertDialog`, `ModalBottomSheet`.
  `TimePicker` is the one exception, for quiet hours: a clock dial is worth more than a hand-built
  copy of one, and it is drawn inside the app's own overlay, themed from the tokens.
- Shared building blocks: `Screen` + `ScreenHeader`, `Section` (titled surface, hairlines between
  rows), `SettingRow`/`SwitchRow` (icon tile + `RowTone`, never a colour), `SelectMenuRow`, `InfoField`,
  `TnTextField`, `SearchBar`, `PrimaryButton`, `Collapsible`, `Avatar`, `ErrorBanner`. New screens
  compose these instead of restyling.
- Icons come from `TnIcon` (lucide, generated); no other icon set.

---

## 7. Quick Checklist (before every commit)

- [ ] No logic in screens or root components; every derivation and handler lives in a ViewModel.
- [ ] One `StateFlow<UiState>` per ViewModel; nothing derivable stored twice.
- [ ] Fragments are stateless and read no store.
- [ ] No `!!`, no `GlobalScope`, no `runBlocking` on main.
- [ ] Long lists are lazy with stable keys.
- [ ] No hex colours, `colorScheme` or Material chrome outside the allow-list (run `:app:testDebugUnitTest`).
- [ ] Feedback via `NoticeCenter`/`SheetCenter`; navigation via `Navigator`.
- [ ] No JSON inside the process: modules exchange Kotlin types (§2).
- [ ] A new case fits one of the §9 patterns, or the pattern is extended rather than bypassed.
- [ ] After any change under `core/`: `./gradlew :core:testDebugUnitTest`, and the §8 rules hold.
- [ ] After any change to the store's SQL or its driver: `./gradlew :core:connectedDebugAndroidTest`.

---

## 8. Messaging Core Rules (`core/`) — NON-NEGOTIABLE

The core owns the entire messaging domain: protocol, transport, storage, outbox, transfers,
signalling and the nebula config schema. These rules exist so it stays **efficient, fast, lean and
reliable**. Nothing outside `core/` may reach past `CoreClient` into them.

### Bounded by construction

- **Everything is bounded**: bounded channels (`PEER_QUEUE_CAP`), protocol-capped frames
  (`MAX_FRAME_BYTES`), a capped number of reassemblies in flight (`MAX_INCOMING_TRANSFERS`),
  stale-entry purges on every map, capped query pages. No unbounded queue or cache, ever.
  Every limit lives in `engine/Limits.kt` with the reason it exists.
- **Zero-copy where it counts**: attachment bytes stream between disk and socket and never
  cross into the app — files travel as paths, however large they are.
- **One scope**: the engine creates a `SupervisorJob` scope on start and cancels it on stop; every
  task it spawns is a child of that scope, and every socket it opens is closed there too. No
  `GlobalScope`, no free-running thread, nothing that outlives `stop()`.
- **The app never polls.** Updates are event-driven: the engine coalesces before anything reaches
  the app (at most one invalidation per kind per 50 ms, transfer progress quantized to ≥2% steps),
  and `CoreInvalidations` coalesces once more before a query re-runs.
- **Coarse reads**: one call returns one complete view model (`chatView` — messages with their
  actions and reply previews in a single read). Per-row or loop-driven reads from a screen are
  forbidden; they were expensive across the old FFI and they are still wrong here.
- **Call signals are not acked by the transport.** `sendSignal` only proves the frame was queued;
  a TCP session to a peer whose tunnel dropped is a zombie that accepts writes. The caller proves
  reachability with `pingPeer` (a failed probe evicts the cached connection and retries over a
  fresh one) before offering, and treats the callee's `call-ringing` as the delivery ack.

### Storage

- The schema, the migrations and every column name are inherited: an install from any earlier
  build opens untouched. A new column is added by a migration, never by editing the DDL.
- **The SQL must run on the SQLite that ships with the oldest supported Android** (3.18 on API 26):
  no UPSERT (`ON CONFLICT … DO UPDATE`), no `VACUUM INTO`, no window functions. `SqlDb` is the
  seam that lets the same statements run against the framework database on a device and a JDBC
  connection in the tests — where the whole store is covered.
- Enum columns are mapped by hand in `db/Wire.kt`. A value a newer build wrote must decode to
  something renderable rather than failing the query: one unexpected row must never empty a screen.

### Concurrency

- Locks are short and never held across a suspension point. The store serialises itself; nothing
  above it needs to know that.
- Blocking socket I/O belongs in `withContext(Dispatchers.IO)`; a blocked reader is woken by
  closing its socket, which is also how a zombie connection is evicted.
- Failures surface as `CoreException` with the kind that went wrong, caught at the `CoreClient`
  boundary and turned into a notice. Nothing is swallowed, nothing reaches `Log` as the only report.

### Dependencies

The core depends on kotlinx.coroutines, kotlinx.serialization and the Android framework — nothing
else. The tests add `sqlite-jdbc` (the store, off-device) and `androidx.test` runner + ext-junit
(the store, on-device). Adding any other dependency requires a written justification added to this
section first.

### Tests

`./gradlew :core:testDebugUnitTest` covers the store (against a real SQLite through JDBC), the wire
codec, the nebula config against the golden fixtures in `core/src/test/resources/nebula`, the outbox
sequencing, the tar archive, and two whole engines talking to each other over loopback. The fixtures
are a contract: changing one changes what nebula receives, and the commit has to say why.

**`AndroidSqlDb` is the one part no unit test can reach**, and the framework database rejects things
JDBC accepts — `execSQL` refuses any statement that can return a row (a `PRAGMA` that sets a value
answers with it), and only a cursor factory can bind a typed argument to a query. Any change to the
store's SQL or to that driver has to run on a device:

```
./gradlew :core:connectedDebugAndroidTest
```

Add a case there for a statement shape the store did not use before.

---

## 9. Patterns in Use

Each of these exists because a class of bug kept recurring without it. A new case of the same
shape uses the pattern; do not reinvent a local variant.

**State machine for anything with a lifecycle** (`CallEngine.Machine`). One sealed value replaced
whole on every transition, each state carrying only the data legal in it, one child `Job` per
attempt so ending it is one cancel, and a single `transition()` that also publishes the public
projection. Illegal combinations (a call id with no call, a link with no tracks) are
unrepresentable. Loose `var` fields that must agree with each other are the smell to look for.

**Spec table over one template for dispatch** (`InboundDispatcher.specFor`). An exhaustive
`when` builds one `Spec` per enum value (what runs, which post-steps apply); the template applies
the shared preamble and post-steps once. A new enum value is a compile error, not a frame that
silently does nothing. The same shape fits any "one handler per kind" dispatch.

**Transition table on a state enum** (`Wire.TransferState.canMoveTo`/`canApply`). Every guard
asks the table instead of comparing against a hand-picked set of states. A redelivered frame may
re-apply an open state; a finished one moves nowhere.

**Strategy per variant** (`ActionKind`). When N `when`s over one enum must agree (how to build the
frame, apply locally, revert), one object per variant holds all N behaviours.

**Facade over aggregate DAOs** (`Store` → `ContactsDao`, `MessagesDao`, …). One lock, created in
the facade and shared, so reentrancy across aggregates holds; batched writes wrapped in one
transaction; callers keep the facade's signatures.

**Requests as data, rendered at the root** (`MediaViewerCenter`/`MediaViewerHost`,
`Notices`/`NoticeHost`). A store holds a plain value saying what to show; a root component `when`s
over it and reads its own data from the graph. Flow collection never lives in a store or view model.

**Sheets carry their content** (`SheetRequest`/`SheetHost`). A request is a title and a composable;
the host draws it. The composable is one of the root components in `SheetHost.kt`, which reads its
own data from the graph, so the lambda a view model builds captures plain values only — an id, a
slot — and never the view model itself. A sheet that captured its screen's state would keep
rendering it after the screen is gone and stop updating when the chat behind it re-queries.

**Sealed modes for mutually exclusive UI state** (`ComposerMode`, `ChatOverlay`). Eight booleans
and nullables that exclude each other become one sealed value in both the view model's local
state and the UiState it projects.

**One reporting boundary** (`NoticeCenter.reporting`, `CoreClient.query`/`command`,
`CoreEvent.EngineFault`, the engine scope's `CoroutineExceptionHandler`). A failure with nobody
to throw to becomes exactly one notice; `CancellationException` is always rethrown; a store that
is not open yet answers quietly, anything else is reported.

**Ownership from the durable row, not the frame** (`Store.ownsTransfer`). A peer is party to a
transfer only if the message row says so on the side the frame implies. Unknown ids are refused.

**Bounded, closable resources** (`AckWaiter` is `AutoCloseable`; `Channel(capacity)` with
`trySend` for anything a reader answers with; `busReducer` for bus-fed state). A registration
that must be forgotten on every exit path is a `use {}` block, never paired `register`/`forget`.

**One-shot in-process handoff for secrets** (`VpnStartHandoff`). Nothing that must not leave the
process rides an Intent or a static; it is offered once, taken once by nonce, and cleared.

**Shared state helpers** (`uiState`, `peerListState`). The sharing policy and the seed rule live
in one place; a view model never spells out `stateIn(..., WhileSubscribed(5_000), ...)` itself.

**Boundary helpers for text that leaves the process** (`QrPayloads`, `Endpoints`, `LogRedaction`).
Parsing and formatting of a wire, QR or stored string is one pure object with a test, never
inline in a view model.
