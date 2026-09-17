# Wire protocol (v1)

Direct TCP between overlay IPv6 addresses on port **4433**.

Frame: `uint32-BE length | UTF-8 JSON envelope`, capped at **256 KiB** per frame
(`core/src/main/kotlin/com/telenebula/core/protocol/FrameCodec.kt`).

```jsonc
{ "v": 1, "type": "msg", "id": "<uuid>", "from": {"ip": "fd..", "name": "Harey"},
  "ts": 1756600000000, "body": "hello", "replyToId": "<uuid>" }
```

Peer identity is taken from the **socket's source overlay IP**, which nebula has already
authenticated — never from the envelope's `from` claim.

## Envelope types

| Group | Types |
| --- | --- |
| Session | `hello`, `hello-ack`, `ping`, `pong` |
| Messages | `msg`, `msg-ack`, `react`, `edit`, `delete`, `seen`, `typing` |
| Attachments | `att-begin`, `att-chunk` |
| Large attachments | `att-offer`, `att-accept`, `att-decline`, `att-cancel`, `att-error` |
| Calls | `call-offer`, `call-ringing`, `call-answer`, `call-ice`, `call-renegotiate`, `call-renegotiate-answer`, `call-cam`, `call-reject`, `call-end` |

The full field set is `core/src/main/kotlin/com/telenebula/core/model/Protocol.kt`.

### Presence

A `pong` carries `presence`: `"online"` while the sender is using the app and shares that, otherwise
`"reachable"`. A pong without the field, from an older build, reads as reachable. A probe that goes
unanswered is what the app shows as offline.

### Large attachments

`att-offer` carries the same payload as `att-begin` but commits to nothing: the receiver answers
`att-accept` (with the chunk to resume from, `0` for a fresh start) or `att-decline` (with a
reason). A sender that changes its mind before streaming sends `att-cancel`; a receiver abandoning a
transfer mid-stream sends `att-error` with a reason.

A sender only offers above `ATT_OFFER_THRESHOLD_BYTES` (5 MiB). Below that it streams directly,
which is also what a peer too old to understand an offer will do — such a peer never sends more than
the old 16 MiB cap, so `ATT_AUTO_ACCEPT_BYTES` accepts exactly the traffic that build could produce.

### Call signalling

Call signals are **not acked by the transport** — a TCP session to a peer whose tunnel dropped is a
zombie that still accepts writes. So the callee's `call-ringing` is the delivery ack, and a caller
proves reachability with `ping` before offering.

## Forward compatibility

An unknown `type` decodes to a sentinel and is ignored, never answered
(`FrameCodec` uses `coerceInputValues = true`). Older builds therefore keep interoperating across
protocol additions: every type listed here that a v1 peer knew still exists and still means the same
thing, and the five `att-*` negotiation types, `call-ringing` and the `presence` field on `pong`
are additions that an older peer simply drops.

The same is **not** true of the on-disk database in the other direction: the schema has since
rebuilt the `message_actions` table (`core/src/main/kotlin/com/telenebula/core/db/Schema.kt`) to
widen a CHECK constraint, and re-queued rows that had only been marked failed by the retired attempt
budget. The migration runs forward automatically on an existing install; it is not reversible by
downgrading, and an older build reading the new rows would not understand the `att-*` action types.
