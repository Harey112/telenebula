# Golden fixtures for the nebula config layer

`NebulaConfigTest` checks the nebula config layer against these files. They were generated once
from the React Native implementation (`src/lib/nebulaConfig.ts`, `src/lib/nebulaAdvanced.ts`,
`src/constants/nebula.ts` at commit `1633618`) and carried unchanged through every rewrite since, so that
the Kotlin app renders byte-for-byte the same config every earlier build did.

The generator is long gone. Treat the files as the contract: a change here is a deliberate change
of what nebula receives, and needs the reason in the commit.

## Deliberate change: `expected_normalized_legacy.json`

Filling a stored config used to be a shallow merge over a JSON tree. The config is a typed Kotlin
structure now, so decoding it *is* the fill, and two things changed on purpose:

- A nested object is completed field by field instead of replacing its default wholesale. A stored
  `firewall.conntrack` carrying only `tcpTimeout` used to leave the other two timeouts missing,
  which the editor then reported as invalid durations.
- A section the schema does not know (`customSection`) is dropped instead of carried along. Nothing
  writes one: the editor always renders the config back from the typed field table.
