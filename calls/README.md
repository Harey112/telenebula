# :calls

Kotlin call engine over `org.webrtc` (artifact `io.getstream:stream-webrtc-android`, pinned in
`gradle/libs.versions.toml`; record the libwebrtc milestone here when bumping — 1.3.10 was the
first version used, milestone unstated in its release notes, 1.3.5 was M125).

- `CallEngine` — the state machine (contacting → ringing → connecting → active), signaling via
  the core's `send_signal`/`ping_peer` through `CoreSignaling`, one `StateFlow<CallState>`.
- `media/` — `WebRtcRuntime` (one factory + shared EGL context per process), `WebRtcSession`
  (peer connection with the app's fixed config), `LocalMedia` (mic + camera), SDP suspend adapters.
- `audio/CallAudio` — focus, `MODE_IN_COMMUNICATION`, single routing authority, ringback/busy tones
  from `assets/`.
- `system/` — CallStyle notifications, the phoneCall foreground service, the notification action
  receiver, the draw-over-apps floating window, overlay permission.
- `render/` — `TextureVideoRenderer` and `VideoTrackBinding` for tiles that need clipping.

The app injects its notification icon and call-screen intent through `CallsConfig` at startup.
