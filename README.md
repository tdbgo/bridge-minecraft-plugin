# Bridge by PLAYCITY BLOCK

Bridge is a Paper plugin that lets trusted web backends and other plugins invoke small, typed server actions without exposing RCON or an arbitrary remote console.

The same Bridge JAR can run on any compatible Paper server. Each web backend receives its own credential, action and event allowlists, rate limit and concurrency limits. Deployments with a central issuer may also use short-lived signed JWT access tokens. Other plugins can add actions and typed events through the separately built `bridge-api` module.

## Why Bridge is useful

- **Safer than RCON:** remote clients can invoke only registered action names with structured payloads.
- **Backend-neutral:** any language or framework that can send JSON over HTTP can call Bridge.
- **Server-neutral:** configuration contains no required PLAYCITY service or external endpoint.
- **Extensible:** server plugins register actions without implementing authentication, HTTP, idempotency or audit logging.
- **Scoped access:** one backend may receive read-only player status while another can receive a specific mutating action.
- **Retry-safe:** caller-generated request IDs coalesce concurrent retries and reject conflicting reuse.
- **Observable:** optional privacy-bounded status and Prometheus endpoints expose aggregate server health.
- **Network-flexible:** use loopback HTTP/WebSocket behind a reverse proxy or VPN, or implement the optional outbound relay protocol for actions and acknowledged event batches when a host cannot accept inbound connections.
- **Real-time when needed:** an optional scoped WebSocket endpoint publishes typed events with sequence numbers, bounded replay and backpressure.
- **Central identity ready:** optional JWT/JWKS verification pins issuer, audience, token type and signature algorithm while retaining local deny-by-default policy.

Bridge is not a public Internet API, a browser authentication system or a generic command executor. Product-level authorization still belongs in the calling backend.

## What makes Bridge different

Bridge is designed as a least-privilege capability bus rather than a remote administration console:

- **Actions, not console access:** integrations receive small, named capabilities instead of a way to run arbitrary server commands.
- **One policy boundary:** provider plugins add typed actions while Bridge retains authentication, caller scopes, limits, idempotency and error handling.
- **Provider ownership:** an action is tied to the plugin that registered it and is removed automatically when that plugin is disabled.
- **Caller-aware retries:** request IDs are isolated by caller, coalesce concurrent retries and reject reuse with a different payload.
- **Risk-aware discovery:** authenticated clients can discover only their permitted actions together with contract version, description and risk classification.
- **Failure-safe relay:** outbound relay execution is durably claimed before a mutating action runs, preventing an uncertain result from being repeated automatically.
- **Secure defaults:** fresh installations expose loopback HTTP only and enable no external relay, teleportation or metrics credentials.

These properties are most useful when several independently deployed web services and server plugins must cooperate without sharing an RCON credential or trusting each integration with the entire Minecraft process.

## Requirements

- Paper 26.2
- Java 25
- A reverse proxy or private network when the web backend is not on the Minecraft host

Folia is not currently supported. Bridge intentionally does not declare `folia-supported: true`.

For a first installation, follow the [minimal static-token quick start](docs/QUICKSTART.md). It configures only the read-only player-status action on loopback before introducing WebSocket, JWT or relay options.

## Build

```powershell
.\gradlew.bat clean build --console=plain
```

Outputs:

- Plugin: `build/libs/Bridge-<version>.jar`
- Developer API: `bridge-api/build/libs/bridge-api-<version>.jar`
- API Javadoc and sources JARs are produced by the `bridge-api` build.

The default development version is `0.5.0-SNAPSHOT`. A release candidate can be built without editing source files:

```powershell
.\gradlew.bat releaseBundle writeReleaseChecksums -PbridgeVersion=0.5.0-rc.1 --console=plain
```

Checksummed artifacts are staged under `build/release`. The stricter `assembleRelease` task additionally refuses snapshot versions, undated changelog entries and unresolved project-license metadata.

Use `publishToMavenLocal` while developing an action-provider plugin:

```powershell
.\gradlew.bat :bridge-api:publishToMavenLocal --console=plain
```

## Install and configure

1. Copy the plugin JAR into the Paper server's `plugins` directory.
2. Start the server once to create `plugins/Bridge/config.yml`.
3. Create one random token file per web backend. Each token must contain at least 32 UTF-8 bytes and should be readable only by the server account.
4. Add a scoped client and enable only the built-in actions you intend to expose.
5. Restart Bridge through a normal server restart. Do not use plugin hot-reload tools.

Example:

```yaml
http:
  enabled: true
  host: "127.0.0.1"
  port: 25585
  allowNonLoopback: false
  token: "CHANGE_ME" # legacy compatibility only
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

Fresh installations do not contact an external relay and do not enable teleportation or metrics. Existing installations keep their saved configuration.

An outbound-only installation can grant separate `relay.allowedActions` and `relay.allowedEvents`. Relay events remain memory-bounded and at-least-once until acknowledged; receivers deduplicate by agent, Bridge instance and sequence and rebuild state when Bridge reports an overflow gap.

Keep Bridge on loopback whenever possible. Put an HTTPS reverse proxy, private VPN or authenticated service mesh between Bridge and a remote web backend. Direct non-loopback binding requires the explicit `http.allowNonLoopback: true` interlock and does not add TLS by itself.

## HTTP usage

Discover actions visible to one client:

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

Reuse a request ID only to retry the exact same action and payload. A timeout does not prove that the action was cancelled.

The complete wire contract is in [docs/HTTP-API.md](docs/HTTP-API.md) and [docs/openapi.yaml](docs/openapi.yaml).

## Add an action from another plugin

Consumer build:

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

Bridge automatically removes actions when their owning plugin is disabled. Payloads and results use immutable JSON-compatible Java maps and lists; the public API does not expose Bridge's JSON implementation.

Providers can similarly register a `BridgeEventDescriptor` and retain the returned `BridgeEventPublisher`. Event publishers remain transport-neutral; Bridge owns WebSocket authentication, replay and connection limits.

See [docs/DEVELOPER-API.md](docs/DEVELOPER-API.md) for naming, threading, lifecycle and failure-code rules.

## Security model

- No arbitrary command action exists.
- Every HTTP client has a distinct credential and local action scope.
- Event scopes are separate from action scopes; existing and legacy clients receive no event access automatically.
- JWT scopes are intersected with the server's local allowlists and can never expand them.
- Request bodies, request IDs, caches, worker threads, rates and concurrency are bounded.
- Action and metrics credentials are separate.
- The default listener is loopback-only.
- Bukkit world/entity work is scheduled onto the Paper primary thread.
- Provider exceptions become stable failures without exposing stack traces to callers.

Action providers remain trusted server code. Installing a malicious plugin is already equivalent to granting code execution inside the Minecraft server process.

## Documentation

- [Quick start](docs/QUICKSTART.md)
- [HTTP API](docs/HTTP-API.md)
- [OpenAPI document](docs/openapi.yaml)
- [Java developer API](docs/DEVELOPER-API.md)
- [Outbound relay protocol](docs/RELAY-PROTOCOL.md)
- [Player status contract](docs/PLAYER-STATUS.md)
- [Observability](docs/OBSERVABILITY.md)
- [WebSocket event protocol](docs/WEBSOCKET-EVENTS.md)
- [JWT authentication](docs/JWT-AUTH.md)
- [Real-time and identity roadmap](docs/FUTURE-AUTH-REALTIME.md)
- [LILO integration study](docs/LILO-INTEGRATION-STUDY.md)
- [Compatibility policy](docs/COMPATIBILITY.md)
- [Release checklist](docs/RELEASE-CHECKLIST.md)
- [Security policy](SECURITY.md)

## License

Bridge source code, binaries and the provider API are licensed under the [Apache License 2.0](LICENSE.md). Required attribution is recorded in [NOTICE](NOTICE), and bundled third-party components remain governed by the licenses listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
