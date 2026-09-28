# Dex web migration parity matrix

The SolidJS frontend is bundled into the debug APK. Each area below is covered by mock-phone
interaction tests and reviewed screenshots where it renders UI. A live phone and TURN call remain
the final manual acceptance pass. Use the current `DexWire.kt` when checking frame names and fields.

| Area | Behaviors to preserve or correct | Port stage |
| --- | --- | --- |
| Session | Login errors and lockout, session restore, logout, expired session, reconnect, client limit, connection banner | 3–4 |
| Navigation | Rail, chats, contacts, calls, settings, narrow single-pane navigation, back and focus restoration | 4 |
| Chat list | Search, archived view, unread, pin, mute, typing, presence, blocked state, latest-message preview | 5 |
| Conversation | Pagination, scroll anchoring, unread divider, reply jump, message actions, send status, retry and cancellation | 5 |
| Covered messages | TAP, ASK, CODE and DEVICE display and reveal rules; direct attachment access follows phone policy | 5 |
| Attachments | Inline safe media, downloads, transfer offers, progress, accept, decline and cancel | 5 |
| Composer | Text, reply, edit, cover toggle, emoji, Enter-to-send, upload progress and abort, voice recording | 5 |
| Contacts | Search, add, edit, delete, change IP, detail, privacy, notification overrides, block, archive and pin | 5–6 |
| Chat info | Media, links, search, notifications, privacy, clear history and related confirmations | 6 |
| Calls | Incoming and outgoing voice/video, mute, camera, ICE restart, TURN relay, move to phone, call logs | 7 |
| Settings | Every current tab, shared versus Dex-only preferences, quick reactions, network and storage actions | 6 |
| Feedback | Errors, notices, prompts, loading and success messages; suppress meaningless repeated Done toasts | 4–7 |
| Accessibility | Keyboard and touch access, labels, focus trap and restoration, visible focus, responsive sizes | 4–7 |

The archived-chat button, repeated “Done” toast, modal input sizing, and add-contact flow have
interaction and screenshot coverage in `web/test/flows.spec.ts`.
