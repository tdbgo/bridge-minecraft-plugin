# Compatibility policy

## Runtime target

Bridge 0.5 is compiled against `paper-api:26.2.build.111-stable` and targets Java 25 bytecode. The plugin descriptor declares Paper API version `26.2`.

- Paper 26.2 with Java 25 is the only intended runtime line.
- Spigot, Bukkit-only servers, Purpur variants and older Paper releases are not claimed compatible.
- Folia is not supported because Bridge currently relies on Paper's primary-thread scheduler and does not declare `folia-supported: true`.
- A reverse proxy or private network is required for remote HTTP/WSS because Bridge does not terminate TLS.

Automated tests, packaging inspection and reproducible-build checks do not substitute for a Paper smoke test. A tagged stable release must complete the manual Paper checklist in [RELEASE-CHECKLIST.md](RELEASE-CHECKLIST.md).

## Provider API compatibility

The separately published `bridge-api` module is the only supported compile-time provider surface. Provider plugins must use it as `compileOnly` and must not shade it.

While Bridge remains below 1.0, patch releases within one minor line should preserve binary compatibility. A minor-version change may revise provider signatures or wire contracts and must include a migration note. Event and action descriptor versions are independent of the Bridge plugin version and must change when their payload contracts become incompatible.

## Transport compatibility

HTTP actions, WebSocket events and outbound relay currently use protocol version `1`. Additive response fields may appear without a protocol-version change; clients must ignore unknown fields. Removing fields, changing their meaning or changing acknowledgement semantics requires a new protocol version.

## Configuration compatibility

The bundled configuration is version 7. Existing saved files remain valid because new WebSocket, JWT and relay-event settings have explicit defaults and are deny-by-default. Bridge does not overwrite an existing configuration during startup. Operators should compare new defaults during upgrades and explicitly grant any new action or event scope.
