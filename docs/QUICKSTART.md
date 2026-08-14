# Bridge quick start

This path configures one backend for the read-only `minecraft.player_status` action over loopback. It deliberately leaves WebSocket, JWT, relay, metrics and mutating actions disabled. Complete this path before enabling another transport.

## 1. Install

Requirements:

- Paper 26.2
- Java 25
- the Bridge plugin JAR in the server's `plugins` directory

Start the server once, stop it normally, and confirm that `plugins/Bridge/config.yml` exists. Do not use a plugin hot-reload tool.

## 2. Create one client token

The token file is relative to `plugins/Bridge` and must contain at least 32 UTF-8 bytes. Keep it readable only by the server account.

Linux or macOS with OpenSSL:

```sh
install -d -m 700 plugins/Bridge/secrets
umask 077
openssl rand -hex 32 > plugins/Bridge/secrets/example-web.token
```

Windows PowerShell:

```powershell
New-Item -ItemType Directory -Force plugins/Bridge/secrets | Out-Null
$tokenBytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($tokenBytes) } finally { $rng.Dispose() }
[Convert]::ToBase64String($tokenBytes) |
    Set-Content -NoNewline -Encoding Ascii plugins/Bridge/secrets/example-web.token
```

Restrict the Windows file ACL to the Minecraft server account before production use. Never commit or paste the resulting token into an issue, log or browser application.

## 3. Configure the minimal client

Edit the existing keys in `plugins/Bridge/config.yml`. Do not add a second `http` or `metrics` section.

```yaml
http:
  enabled: true
  host: "127.0.0.1"
  port: 25585
  allowNonLoopback: false
  token: "CHANGE_ME"
  clients:
    - id: "example-web"
      tokenFile: "secrets/example-web.token"
      allowedActions:
        - "minecraft.player_status"
      allowedEvents: []
      requestsPerMinute: 120
      maxConcurrent: 4
      maxEventConnections: 0

events:
  enabled: false

jwt:
  enabled: false

relay:
  enabled: false

metrics:
  enabled: false

actions:
  teleportPlayer:
    enabled: false
```

Start the server normally. In the console, run `bridge status`; in game, an operator can run `/bridge status`. Expect HTTP to be listening on `127.0.0.1:25585`, one configured client and HTTP access configured as `yes`.

## 4. Verify the API

Liveness does not require a credential:

```sh
curl -fsS http://127.0.0.1:25585/v1/health
```

Expect `tokenConfigured: true` and `httpClientsConfigured: 1`.

On Linux or macOS, load the token without printing it and discover the permitted actions:

```sh
BRIDGE_TOKEN="$(tr -d '\r\n' < plugins/Bridge/secrets/example-web.token)"
curl -fsS \
  -H "Authorization: Bearer $BRIDGE_TOKEN" \
  http://127.0.0.1:25585/v1/actions
```

On Windows PowerShell:

```powershell
$bridgeToken = (Get-Content plugins/Bridge/secrets/example-web.token -Raw).TrimEnd([char[]]"`r`n")
$headers = @{ Authorization = "Bearer $bridgeToken" }
Invoke-RestMethod -Headers $headers http://127.0.0.1:25585/v1/actions
```

The response should disclose `minecraft.player_status` and no mutating action. Test a known player UUID:

```sh
curl -fsS \
  -H "Authorization: Bearer $BRIDGE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"requestId":"quickstart.1","action":"minecraft.player_status","targetPlayer":{"uuid":"00000000-0000-0000-0000-000000000000"}}' \
  http://127.0.0.1:25585/v1/actions
```

Use a new request ID for a new logical operation. Reuse the same ID only when retrying the exact same request.

## 5. Choose the next path

| Need | Add | Relative difficulty | Start with |
| --- | --- | --- | --- |
| A backend calls typed actions | Static-token REST | Low | This guide |
| A backend receives live server events | WebSocket plus event scopes | Medium | [WebSocket protocol](WEBSOCKET-EVENTS.md) |
| Several services share a central issuer | JWT/JWKS | High | [JWT authentication](JWT-AUTH.md) |
| The Minecraft host cannot accept inbound traffic | Outbound relay and a receiver service | High | [Relay protocol](RELAY-PROTOCOL.md) |
| Another Paper plugin provides actions or events | `bridge-api` | Medium | [Developer API](DEVELOPER-API.md) |

Static tokens are the recommended starting point for one or a few trusted server-side backends. Add JWT only when an issuer, JWKS rotation and audience policy already exist. Add relay only when the network requires outbound-only connectivity and the web side can implement its leasing, deduplication and resynchronization responsibilities.

## Common failures

- `bridge_token_not_configured`: no valid static client or JWT configuration was loaded; check `/bridge status` and the server log.
- `401 unauthorized`: the header token does not exactly match the token file, or the client entry was rejected.
- An empty action list: authentication succeeded, but the client has no matching `allowedActions`.
- HTTP is not running: another process owns the port, the host is non-loopback without the explicit interlock, or startup failed; inspect the server log.
- Remote clients cannot connect to `127.0.0.1`: keep Bridge on loopback and add an HTTPS reverse proxy, VPN or private tunnel. Do not expose the plain listener directly to the Internet.

See the complete [HTTP contract](HTTP-API.md) and [OpenAPI document](openapi.yaml) after the first request succeeds.
