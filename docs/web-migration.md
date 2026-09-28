# Dex web frontend: Kotlin/JS to TypeScript + SolidJS

The rules the new code follows are in [AGENTS.md §10](../AGENTS.md). This is the order of work.

## Current checkpoint

- The Vite/SolidJS bundle is now shipped by Android. `bundleDexWeb` copies hashed Vite assets and
  writes an allowlist that `WebAssets` enforces. The Kotlin/JS source, Gradle target and Yarn
  repository have been removed. `assembleDebug` succeeds and the APK contains only the Vite page,
  its hashed script and stylesheet, favicon and phone emoji catalogue.
- The TypeScript wire models, discriminator unions, codec, shared icon generator, and two-way JSON
  fixtures are in place. `WireContractTest` checks field names, types, optionality, enums and frame
  names against the phone. Text sends now carry a request ID that the phone echoes in a completion
  or error, so a covered message can be confirmed without exposing or guessing its body.
- The HTTP API, socket, Solid store reducer/runtime, and pure chat, contact, call, profile,
  address and formatting helpers are in place. Playwright has a scripted mock phone. Call media
  frames are queued with a cap for the later call controller.
- The browser UI includes chat timelines and message tools, attachments and uploads, voice recording,
  contact management, chat info, all settings categories, call history and a WebRTC call seat. The
  Dex-only profile is edited in the web settings. Forms keep their drafts on phone refusals and
  routine Done replies stay quiet.
- `npm run check`, `:app:testDebugUnitTest`, `:dex:testDebugUnitTest`, and 128 Chromium/Firefox
  Playwright cases across 360, 768, 1024 and 1440 px passed after the cutover work. The browser
  screenshots were reviewed. The APK asset list was inspected after `assembleDebug`.
- A live device call through TURN and a full browser pass against a running phone remain manual
  verification. No Android device is attached in this workspace. Local WebKit cannot launch on
  this Fedora ARM host; Ubuntu CI is configured to run it.

## Why

The Kotlin/JS frontend patches the DOM by hand (`Dom.kt`, `node.clear()` and rebuild per row,
outside-click checks per component). Every recent browser bug came from that layer: rows rebuilt
under a click, an event target cast to the wrong DOM type, layout that only broke at some widths.
Solid replaces hand patching with fine-grained reactive updates, TypeScript uses the browser's own
DOM types, and the Playwright suite makes every screen seen at real sizes before it ships.

## What does not change

- The Dex wire and phone-side behavior remain the migration's compatibility contract. The app's
  asset packaging and allowlist must change at cutover to serve Vite's hashed files; no server
  authentication, messaging or TURN behavior changes for the frontend rewrite.
- The browser still receives `index.html`, scripts, stylesheets and `favicon.svg` from
  `assets/dex/`, under the server's page CSP (`script-src 'self'`, no inline scripts).
- The wire: frame names, field names and semantics stay exactly as `DexWire.kt` defines them.
- What the page offers: the migration is feature parity first; visual changes come after.

## Cutover

`web/src/` now contains only TypeScript and SolidJS. The phone serves the Vite bundle under the
same Dex origin and CSP. The old Kotlin/JS files were archived locally before removal at
`/tmp/telenebula-kotlinjs-precutover.tar` to preserve this working tree's uncommitted changes.
The device checklist below is still the final manual acceptance pass.

The feature inventory and acceptance matrix live in [web-parity.md](web-parity.md). Before each
stage, compare the current wire and working tree against that baseline: Dex hardening and UI work
are already in progress on this branch, and the migration must preserve them.

## Historical implementation stages

The completed migration followed this staged plan. The checklist below remains as the record of
the intended cutover sequence; the current implementation status is in the checkpoint above.

### 0. Baseline, then toolchain

- Record the current Kotlin/JS behavior in `web-parity.md`, including known bugs to fix rather
  than reproduce. Run the existing Kotlin/JS, Dex and app test gates before replacing code.

- `web/package.json` (solid-js; dev: vite, vite-plugin-solid, typescript, eslint,
  typescript-eslint, eslint-plugin-solid, vitest, @solidjs/testing-library, jsdom,
  @playwright/test), exact versions, committed lockfile.
- `tsconfig.json` (strict, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`,
  `jsx: preserve`, `jsxImportSource: solid-js`), `vite.config.ts` (output `web/build/dist`, hashed
  file names, no inline assets beyond small SVG), `eslint.config.js` (solid recommended rules as
  errors, no `any`, no hex colours outside `ui/theme.css`, no `innerHTML`).
- `web/build.gradle.kts`: the Kotlin/JS target stays for now; add the Node plugin
  (`com.github.node-gradle.node`, pinned Node, downloaded from the `org.nodejs` ivy repository
  already in `settings.gradle.kts`) with tasks `npmInstall` configured for `npm ci`, `webBuild` (`vite build`), `webCheck`
  (`tsc --noEmit`, eslint, vitest) and `webE2e` (Playwright). `webCheck` joins `testDebugUnitTest`.
  The Yarn repository is removed at stage 8 with Kotlin/JS.
- A stub `<App/>` that mounts, so the pipeline is proven end to end. Run it through an E2E test
  immediately. Add a smoke test that serves the built bundle with Dex's real CSP and MIME headers;
  the stub alone cannot prove this while Kotlin/JS remains packaged.
- CI installs Playwright's pinned browsers and runs `webE2e` for web changes.

### 1. Wire, icons, contract

- `src/wire/`: every `DexWire.kt` class as a TypeScript type (enums as string-literal unions with
  the same serial names), `ServerFrame`/`ClientFrame` as unions discriminated on `t`, and one
  `decodeFrame(text): ServerFrame | null` that validates `t` and the fields a reducer relies on.
- Extend `WireContractTest` beyond its current name-only regex checks: compare field presence,
  optionality, enum values and frame discriminators, then exercise Kotlin-encoded server fixtures
  in TypeScript and TypeScript-encoded client fixtures in Kotlin. While both frontends exist it
  checks both.
- `scripts/gen-icons.py` writes `src/ui/icons.gen.tsx` (one typed `<Icon name=…/>` over a map of
  lucide paths) alongside the Kotlin file; the Kotlin/JS `Icons.kt` output goes at stage 7.

### 2. Core: socket, API, store, logic

- `net/socket.ts` (reconnect with the same backoff, ping, close codes 1008/1013 as today),
  `net/api.ts` (session, login, logout, upload with progress and abort).
- `store/`: one `createStore` holding identity, chats, contacts, presence, typing, queues, tunnel,
  call state, settings, the open chat view and screen state; a reducer from `ServerFrame` to store
  updates using each list's actual key (`id` for messages, `peer` for chats, `ip` for contacts);
  actions that send `ClientFrame`s and track their
  acknowledgement (`Done`/`Error` by ref, as `Replies.kt` does).
- `logic/`: straight ports of `ChatRules`, `Replies`, `ChatLists`, `ContactLists`, `CallLists`,
  `ProfileEdits`, `SettingsModel`, `Effective`, `Format`, `Overlay`, with their tests ported to
  vitest first so the behaviour is pinned before the code moves.
- `web/test/mock-phone.ts`: the mock phone (HTTP + WebSocket speaking the wire, scripted state,
  a frame log), replacing the ad-hoc Python one used so far; it drives both dev (`npm run dev`)
  and the Playwright suite.

### 3. Kit and shell

- `ui/theme.css` (tokens mirroring §6, light and dark), `ui/kit`: Button, IconButton, Row,
  Section, Switch, Select, TextField, SearchBar, Avatar, Popover (anchor positioning, flips at
  edges, outside click and Escape in one place), Menu, Dialog, Prompt, Toasts, HelpButton — all
  through `<Portal>` where they overlay.
- Shell: login, rail, list/chat/info panes with container queries, the narrow-width single-pane
  mode, the connection banner.
- First Playwright baselines: login and the empty shell at all four widths, three engines.

### 4. Chats

- Chat list (search, archived, unread, pinned, mute, typing, presence).
- Conversation: timeline with `<For>` over reconciled messages, day separators, scroll anchoring
  (stay at bottom, keep position on load-more, jump to reply), unread divider, covered messages
  and their reveal steps, attachments (image, video, file, voice with playback), transfer offers
  and progress, send states and retry.
- Message tools (react, reply, more) as one hover/touch toolbar; the reaction bar and the message
  menu as kit Popovers; the emoji picker as a kit Dialog with real tabs.
- Composer: text, reply/edit modes as one union, cover toggle, attachments with upload progress
  and cancel, voice recording (`media/recorder.ts`), emoji, Enter-to-send from the profile.
- Playwright: every message kind in both directions, menus opened with real pointer and touch,
  at all widths.

### 5. Contacts and chat info

- Contact list, add-contact flow, contact detail (stats, connection, certificate, calls), chat
  info (media, links, search, privacy, notifications, flags, danger zone), help buttons.

### 6. Settings

- Every settings tab (status, chats, notifications, privacy, network, storage, diagnostics,
  account, about) with the watch/unwatch of sections as today.
- Complete non-call parity against `web-parity.md` while Kotlin/JS still ships.

### 7. Calls

- `call/media.ts`: the WebRTC seat (offer/answer, ICE with the phone-issued TURN server,
  restart, camera on/off, move to phone), ported from `CallMedia.kt` with its state as one union.
- Call overlay, incoming ring, call logs.
- Verified on a real phone with a real call through the TURN relay (the one stage Playwright
  cannot fully cover).

### 8. Cutover and cleanup

- Full Playwright pass and a manual pass on the device checklist below while Kotlin/JS still ships.
- `bundleDexWeb` copies the Vite output recursively and fails if any referenced asset is missing.
  `WebAssets` permits only files in the generated bundle manifest, including the shared emoji
  catalogue. Verify hashed JS and CSS load from an installed APK under the real CSP.
- In the same reviewed cutover change, delete `web/src/jsMain`, `jsTest`, the Kotlin/JS plugin,
  Yarn repository, old browser half of `WireContractTest` and `gen-icons.py`'s Kotlin/JS output;
  update AGENTS.md §2. Keep the Kotlin side of the wire contract.

## Device acceptance checklist

Chrome, Firefox and Safari on a laptop, and Chrome on an Android tablet: log in, open every tab,
send and receive text, a photo, a video, a file and a voice message, react, reply, edit, delete,
reveal a tap and an ask message, see a code message withheld, add a contact, change a setting,
make and receive a video call through the relay, move it to the phone, log out.

## Risks

- **WebKit differences** (media recording formats, `getUserMedia` on insecure-looking origins with
  a self-signed certificate): covered by the WebKit Playwright project for layout; recording and
  calls are checked on a real Safari.
- **Bundle and CSP**: Vite must not emit inline scripts or module preload polyfills that the
  page CSP refuses; stage 0 proves the built page loads under the server's real headers.
