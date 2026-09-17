# TeleNebula

Private messaging and calls between Android phones, with **no server in the middle**. Messages and
video calls travel directly from one phone to the other inside a private
[Nebula](https://github.com/slackhq/nebula) network that only its members can reach. The one shared
piece is your own nebula lighthouse, and all it does is help two phones find each other.

[![CI](https://github.com/Harey112/telenebula/actions/workflows/android.yml/badge.svg)](https://github.com/Harey112/telenebula/actions/workflows/android.yml)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-brightgreen)

## What you need

1. **An Android phone**, 8.0 or newer.
2. **A nebula mesh with a lighthouse** on a public IP, run by you or by whoever runs your network.
3. **Three certificate files issued for your phone**: `ca.crt`, `host.crt`, `host.key`, handed to
   you privately by whoever runs the mesh.

Your identity is that certificate: its name is your username and its overlay IPv6 address is your
"phone number".

## Features

- **Messaging**: text, replies, reactions, edits, forwarding, delete for me or for everyone, read
  receipts, typing indicators, disappearing messages, pin, archive, mute, block, chat export.
- **Calls**: WebRTC audio and video, camera on or off at any time, ringing through Android's call
  notification, and a floating window that survives leaving the app.
- **Attachments**: photos, videos and files of any size, streamed phone to phone; large files are
  offered first and an accepted transfer resumes where it stopped.
- **Status**: online, reachable or offline, learnt when phones ping each other, with a switch and a
  pause so you decide what others see.
- **Nothing gives up**: a message to a phone that is off waits and leaves the moment it comes back.
- **Private by construction**: traffic is encrypted and mutually authenticated by nebula and never
  leaves the mesh. The private key lives in the Android Keystore and is handed to nothing but the
  tunnel. Read receipts, typing and screenshots can be controlled per chat.
- **Your network, your rules**: every nebula option that applies to an Android node is editable in
  the app. See [advanced nebula settings](docs/nebula-config.md).

## How it works

```
+---------------------- Android phone A ----------------------+
| TeleNebula                                                  |
|  ├─ :app    Compose UI (chats / contacts / calls / me)      |
|  ├─ :core   messaging: SQLite, outbox, TCP [::]:4433        |
|  ├─ :calls  WebRTC, host candidates only, no STUN/TURN      |
|  └─ :vpn    VpnService → embedded nebula (gomobile)         |
+-----------------------------│-------------------------------+
               fdxx::/64 overlay (Noise, mutual certs)
                              │      UDP underlay, hole-punched
                   lighthouse (public IPv4:4242)
                              │
+---------------------- Android phone B ----------------------+
```

Messaging is direct TCP between overlay addresses, one JSON envelope per length-prefixed frame.
Every remote effect is an action on a per-peer queue: a peer is probed once, and only if it answers
does its whole queue move. Calls carry their signalling over that same channel, and media flows
directly between the two phones. Details in [the wire protocol](docs/protocol.md).

Built with Kotlin and Jetpack Compose throughout, including the messaging core, with the official
[mobile_nebula](https://github.com/DefinedNet/mobile_nebula) bindings for the tunnel and libwebrtc
for calls.

## Getting started

**Install.** Download the APK for your phone from the
[latest release](https://github.com/Harey112/telenebula/releases/latest), open it, upload the three
certificate files, enter the lighthouse's nebula IPv6 and its public IP and port, and tap **Create
identity & connect**. Android asks once to allow the VPN. Add a contact by their overlay IPv6 or by
scanning their QR code.

**Run the mesh.** If that is you: issue a nebula CA and one certificate per phone with an IPv6
overlay address, and run a lighthouse reachable on a public IP and port. Give each phone its own
`ca.crt`, `host.crt` and `host.key` privately, and never ship `ca.key` to a device.

**If two phones cannot reach each other**, both are usually behind carrier NAT that hole punching
cannot cross. Run the lighthouse with `relay: { am_relay: true }` in its nebula config. The app
already lists the lighthouse as each phone's relay, so traffic between them falls back to it with
no change on the phones.

**Build.** JDK 17 and an Android SDK with platform 37.2 and build-tools 36.0.0; Gradle comes with
the wrapper.

```bash
./gradlew assembleDebug
./gradlew lint testDebugUnitTest
```

The rules the code follows are in [AGENTS.md](AGENTS.md).
