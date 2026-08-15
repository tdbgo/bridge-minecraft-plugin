# Bridge by PLAYCITY BLOCK

Bridge is a Paper plugin that lets trusted web backends and other plugins invoke small, typed server actions and receive scoped events, without exposing RCON or an arbitrary remote console.

*[한국어 안내서](README.ko.md)*

Each caller receives its own credential, action and event allowlists, rate limit and concurrency limit. Deployments with a central issuer can additionally verify short-lived signed JWT access tokens. Other Paper plugins register their own actions and events through the separately published `bridge-api` module.

Bridge is not a public Internet API, a browser authentication system or a generic command executor. Product-level authorization stays in the calling backend.

## Why Bridge

- **Actions, not console access.** Callers invoke registered action names with structured payloads. No arbitrary command action exists.
- **One policy boundary.** Provider plugins add typed actions and events; Bridge keeps authentication, caller scopes, limits, idempotency and error handling.
- **Least privilege.** One backend can hold read-only player status while another holds a single mutating action. JWT scopes are intersected with local allowlists and can never widen them.
- **Retry-safe.** Caller-scoped request IDs coalesce concurrent retries and reject reuse with a different payload.
- **Network-flexible.** Use loopback HTTP and WebSocket behind a reverse proxy or VPN, or the outbound relay when a host cannot accept inbound connections.
- **Observable.** Optional status and Prometheus endpoints expose aggregate health only, with a credential separate from every action client.
- **Secure by default.** A fresh installation binds loopback HTTP only and leaves WebSocket, JWT, relay, metrics and mutating actions disabled.
- **Backend- and server-neutral.** Any language that can send authenticated JSON over HTTP works, and the configuration names no required external service.

These properties matter most when several independently deployed services and server plugins must cooperate without sharing one RCON credential.

## Requirements

- Paper 26.2
- Java 25
- A reverse proxy or private network when the backend is not on the Minecraft host

Folia is not supported; Bridge intentionally does not declare `folia-supported: true`. Spigot, Bukkit-only and Purpur servers are not claimed compatible. See [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).

For a first installation, follow the [minimal static-token quick start](docs/QUICKSTART.md). It configures only the read-only player-status action on loopback before introducing WebSocket, JWT or relay options.

## Built-in capabilities

| Action | Risk | Configuration gate |
| --- | --- | --- |
| `minecraft.player_status` | `READ_ONLY` | client allowlist only |
| `minecraft.teleport_player` | `MUTATING` | `actions.teleportPlayer.enabled`, default `false` |
| `minecraft.notice_sync` | `MUTATING` | `actions.notice.syncEnabled`, default `false`, needs the optional Notice plugin |
| `minecraft.notice_send` | `MUTATING` | `actions.notice.sendEnabled`, default `false`, needs the optional Notice plugin |

| Event | Data |
| --- | --- |
| `minecraft.player_joined` | player UUID only |
| `minecraft.player_left` | player UUID only |

Enabling an action or event server-wide is not enough on its own. Each client must also list it in `allowedActions` or `allowedEvents`.

## Build

```powershell
.\gradlew.bat clean build --console=plain
```

Outputs:

- Plugin: `build/libs/Bridge-<version>.jar`
- Developer API: `bridge-api/build/libs/bridge-api-<version>.jar`, with Javadoc and sources JARs

The default development version is `0.5.0-SNAPSHOT`. Build a release candidate without editing source files:

```powershell
.\gradlew.bat releaseBundle writeReleaseChecksums -PbridgeVersion=0.5.0-rc.2 --console=plain
```

Checksummed artifacts are staged under `build/release`. The stricter `assembleRelease` task additionally refuses snapshot versions, undated changelog entries and unresolved license metadata.

Use `publishToMavenLocal` while developing a provider plugin:

```powershell
.\gradlew.bat :bridge-api:publishToMavenLocal --console=plain
```

## Install and configure

1. Copy the plugin JAR into the Paper server's `plugins` directory.
2. Start the server once to create `plugins/Bridge/config.yml`.
3. Create one random token file per backend. Each token needs at least 32 UTF-8 bytes and should be readable only by the server account.
4. Add a scoped client and enable only the actions you intend to expose.
5. Apply changes with a normal server restart. Do not use plugin hot-reload tools.

```yaml
http:
  enabled: true
  host: "127.0.0.1"
  port: 25585
  allowNonLoopback: false
  token: "CHANGE_ME" # legacy single-client credential; prefer the clients list
  clients:
    - id: "example-web"
      tokenFile: "secrets/example-web.token"
      allowedActions:
        - "minecraft.player_status"
        - "minecraft.teleport_player"
      allowedEvents:
        - "minecraft.player_joined"
        - "minecraft.player_left"
      requestsPerMinute: 120
      maxConcurrent: 4
      maxEventConnections: 2

actions:
  teleportPlayer:
    enabled: true
    allowedWorlds: ["world"]
```

Operators can check the result with `bridge status` in the console or `/bridge status` in game.

Keep Bridge on loopback whenever possible and put an HTTPS reverse proxy, VPN or authenticated service mesh between Bridge and a remote backend. Direct non-loopback binding requires the explicit `http.allowNonLoopback: true` interlock and does not add TLS by itself.

An outbound-only installation grants separate `relay.allowedActions` and `relay.allowedEvents`. Relay events are memory-bounded and at-least-once until acknowledged; receivers deduplicate by agent, Bridge instance and sequence, and rebuild state when Bridge reports an overflow gap.

## Commands and permissions

| Command | Purpose | Permission |
| --- | --- | --- |
| `/bridge help` | Show usage | `bridge.use` |
| `/bridge status` | HTTP listener, WebSocket, relay, credential and registered-action state | `bridge.use` |
| `/bridge reload` | Re-read configuration and restart Bridge-owned transports | `bridge.admin` |

Both permissions default to `op`, and `bridge.admin` includes `bridge.use`. The same subcommands work from the server console without the `/` prefix.

## Configuration, storage and network

Bridge keeps its configuration in `plugins/Bridge/config.yml`. The only other files it uses are client and metrics token files chosen by the operator, an optional local JWKS file and, when the relay is enabled, `relay.identityFile` (default `secrets/relay-identity.json`) plus the adjacent `relay-executions.json` retry journal. Relative paths resolve from `plugins/Bridge`.

- No database is required.
- No client-side mod is required; Bridge is server-side only.
- No dependency is downloaded at runtime. Everything needed is inside the plugin JAR.
- No PLAYCITY or other vendor endpoint is required. The default configuration names no external service.
- Outbound connections happen only when explicitly configured: a remote `jwt.jwksUrl` for key retrieval, or `relay.baseUrl` when the relay is enabled. Both are empty by default.

## HTTP usage

Discover the actions one client may call:

```http
GET /v1/actions
Authorization: Bearer <client-token>
```

Execute an action:

```http
POST /v1/actions
Content-Type: application/json
Authorization: Bearer <client-token>

{
  "requestId": "example.status.20260814.001",
  "action": "minecraft.player_status",
  "targetPlayer": {
    "uuid": "00000000-0000-0000-0000-000000000000"
  }
}
```

Reuse a request ID only to retry the exact same action and payload. A timeout does not prove the action was cancelled.

The complete wire contract is in [docs/HTTP-API.md](docs/HTTP-API.md) and [docs/openapi.yaml](docs/openapi.yaml).

## Add an action from another plugin

```kotlin
dependencies {
    compileOnly("com.playcity:bridge-api:<version>")
}
```

Declare `depend: [Bridge]` or an equivalent Paper dependency, then register during `onEnable`:

```java
BridgeApi bridge = getServer().getServicesManager().load(BridgeApi.class);
BridgeRegistration registration = bridge.registerAction(
    this,
    new BridgeActionDescriptor(
        "example:greet",
        "1",
        "Returns a greeting without changing server state.",
        BridgeActionRisk.READ_ONLY
    ),
    (request, context) -> CompletableFuture.completedFuture(
        BridgeActionResult.success(Map.of("message", "Hello"))
    )
);
```

Bridge removes an action automatically when its owning plugin is disabled. Payloads and results use immutable JSON-compatible Java maps and lists; the public API does not expose Bridge's JSON implementation.

Providers can likewise register a `BridgeEventDescriptor` and retain the returned `BridgeEventPublisher`. Publishing stays transport-neutral; Bridge owns WebSocket authentication, replay and connection limits.

See [docs/DEVELOPER-API.md](docs/DEVELOPER-API.md) for naming, threading, lifecycle and failure-code rules.

## Upgrades

1. Back up `plugins/Bridge/config.yml` and any token, JWKS or relay identity files.
2. Compare the new bundled defaults against your saved configuration and explicitly grant any new action or event scope you want.
3. Replace the JAR and apply the change with a normal server restart.

The bundled configuration is version 7. Existing version-6 files remain readable because new WebSocket, JWT and relay-event settings have explicit deny-by-default values. Bridge never overwrites an existing configuration during startup, so new keys appear only in the bundled defaults you compare against.

## Limitations and RCON migration

Bridge narrows what a remote caller can do, but installing it does not remove RCON on its own. RCON is fully retired only after every workflow has a typed action, every consumer has been migrated, the migration has been verified, and the RCON listener and credential are then disabled.

Bridge deliberately does not provide:

- a generic console or command-execution action;
- TLS termination — put a reverse proxy, VPN or service mesh in front for remote access;
- browser authentication, login or account linking;
- Folia support;
- durable event delivery. WebSocket replay is memory-only and does not survive a Bridge restart; consumers must rebuild state from an authoritative query.

## Security model

- No arbitrary command action exists.
- Every HTTP client has a distinct credential and local action scope.
- Event scopes are separate from action scopes; existing and legacy clients receive no event access automatically.
- JWT scopes are intersected with the server's local allowlists and can never expand them.
- Request bodies, request IDs, caches, worker threads, rates and concurrency are bounded.
- Action and metrics credentials are separate.
- The default listener is loopback-only.
- Bukkit world and entity work is scheduled onto the Paper primary thread.
- Provider exceptions become stable failures without exposing stack traces to callers.

Action providers remain trusted server code. Installing a malicious plugin is already equivalent to granting code execution inside the Minecraft server process. Report vulnerabilities through the private route in [SECURITY.md](SECURITY.md).

## Troubleshooting and support

Start with `/bridge status` and the server log. Common first-run failures:

| Symptom | Likely cause |
| --- | --- |
| `bridge_token_not_configured` | No valid static client or JWT configuration loaded |
| `401 unauthorized` | Header token does not exactly match the token file, or the client entry was rejected |
| Empty action list | Authentication succeeded, but the client has no matching `allowedActions` |
| HTTP not listening | Port already in use, non-loopback host without `http.allowNonLoopback: true`, or a startup failure |
| Remote client cannot reach `127.0.0.1` | Expected. Add an HTTPS reverse proxy, VPN or private tunnel instead of exposing the listener |
| Client receives no events | Events disabled, or the client has no `allowedEvents` and `maxEventConnections` |

Report bugs and feature requests at <https://github.com/tdbgo/bridge-minecraft-plugin/issues>. Report vulnerabilities through the private route in [SECURITY.md](SECURITY.md), never a public issue.

## Documentation

| Document | Purpose |
| --- | --- |
| [Quick start](docs/QUICKSTART.md) | First loopback installation with one read-only action |
| [HTTP API](docs/HTTP-API.md) | Endpoints, authentication and failure codes |
| [OpenAPI document](docs/openapi.yaml) | Machine-readable HTTP contract |
| [Player status contract](docs/PLAYER-STATUS.md) | `minecraft.player_status` request and result shape |
| [WebSocket events](docs/WEBSOCKET-EVENTS.md) | Event subscription, replay and backpressure |
| [JWT authentication](docs/JWT-AUTH.md) | Signed-token contract and scope intersection |
| [Outbound relay protocol](docs/RELAY-PROTOCOL.md) | Receiver responsibilities for outbound-only hosts |
| [Observability](docs/OBSERVABILITY.md) | Status snapshot, Prometheus metrics and privacy boundary |
| [Developer API](docs/DEVELOPER-API.md) | Writing an action or event provider plugin |
| [Compatibility policy](docs/COMPATIBILITY.md) | Runtime, provider API, transport and configuration compatibility |
| [Real-time and identity roadmap](docs/FUTURE-AUTH-REALTIME.md) | Rationale for WebSocket and JWT, and remaining work |
| [LILO integration study](docs/LILO-INTEGRATION-STUDY.md) | Why LILO stays an optional Bridge integration |
| [Release checklist](docs/RELEASE-CHECKLIST.md) | Automated, Modrinth and manual Paper release gates |
| [Contributing](CONTRIBUTING.md) | Build, boundaries and compatibility review |
| [Security policy](SECURITY.md) | Supported versions and vulnerability reporting |
| [Changelog](CHANGELOG.md) | Released changes by version |

## License

Bridge source code, binaries and the provider API are licensed under the [Apache License 2.0](LICENSE.md). Required attribution is recorded in [NOTICE](NOTICE), and bundled third-party components remain governed by the licenses listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
