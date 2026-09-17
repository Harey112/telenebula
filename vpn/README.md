# :vpn

Android `VpnService` hosting nebula through DefinedNet's gomobile bindings
(`net.defined:mobileNebula`, resolved from `local-maven/` — a module-local Maven repo, because
AGP forbids `files(...)` AAR dependencies inside a library module).

- `NebulaVpnService` — the tun: claims the overlay networks and unsafe routes, starts nebula,
  rebinds on network changes. Unchanged from the React Native era apart from the package.
- `NebulaState` — process-wide tunnel state as a `StateFlow`, written by the service.
- `NebulaVpnController` — the app's only entry point: certificate helpers, start/stop/reload,
  hostmap diagnostics, log tail.

Rebuild the AAR only when upstream mobile_nebula changes: `scripts/build-nebula-aar.sh`
(needs Go + Android SDK/NDK). It is built with `-androidapi=26`, hence `minSdk 26` everywhere.
