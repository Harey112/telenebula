# Advanced nebula settings

Every option of the official nebula [`examples/config.yml`](https://github.com/slackhq/nebula/blob/master/examples/config.yml)
that applies to an Android node is editable in the app, under **Advanced nebula settings** — on the
setup screen, and later at Settings → Network → Lighthouse.

Editable:

- **PKI** blocklist
- **Static hosts** and DNS re-query
- **Lighthouse** allow lists and advertised addresses
- **Listen** socket
- **NAT punching**
- **Cipher**
- **Relays**
- **Tun** MTU and routes, and unsafe routes (also installed into the VPN)
- **Logging**, **stats**, **handshakes**, **tunnels**
- **Firewall** actions, conntrack and rules
- **sshd**

`:core` owns the schema, the defaults, the rendering and the validation — the app reads the config
as typed data (`listen.port`, `tun.mtu`, the restart-vs-reload comparison) and never re-implements a
rule. Because the config is a typed `NebulaAdvancedConfig`, a partial or older stored profile fills
itself in from the field defaults.

Two things are not yours to break:

- The **firewall always keeps the locked rules the app needs**: TCP on the message port, UDP for
  call media, ICMP.
- Windows-only, macOS-only and host-Linux-only knobs are fixed by the platform and not exposed.

Saving reloads the running tunnel. It restarts it instead when `listen`, routines, MTU or unsafe
routes changed, since those cannot be applied to a live tunnel.

The rendered config is validated against golden fixtures in `core/src/test/resources/nebula`. Those
fixtures are a contract: changing one changes what nebula receives, and the commit has to say why.
