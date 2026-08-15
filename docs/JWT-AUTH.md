# JWT authentication

Bridge 0.5 can validate signed JWT access tokens alongside existing static service credentials. JWT is disabled by default and does not make Bridge a login server.

## Required token contract

Bridge pins and validates all of the following:

- JWS algorithm from `jwt.algorithm`, default `RS256`
- JOSE `typ` from `jwt.requiredType`, default `at+jwt`
- exact `iss` and `aud`
- required `sub`, `iat`, `exp` and scope claim
- `iat` not beyond configured clock skew and `exp - iat` within `jwt.maxLifetimeSeconds`
- signature against a configured local or HTTPS JWKS
- configurable bounded clock skew

The optional `client_id` claim contributes to a privacy-bounded caller identifier. Bridge hashes the subject before using it in logs or idempotency keys.

## Scope mapping

Default scope names are:

```text
bridge:action:minecraft.player_status
bridge:action:minecraft.teleport_player
bridge:event:minecraft.player_joined
bridge:event:minecraft.player_left
```

The scope claim may be a space-separated string or a string array. Prefix rules such as `bridge:event:minecraft.player_*` are supported.

Token scopes are always intersected with `jwt.allowedActions` and `jwt.allowedEvents` from the local server configuration. For example, a token containing `bridge:action:*` still cannot call anything when the local action list is empty. This makes the Minecraft server's configuration an independent deny-by-default boundary.

## Configuration example

```yaml
jwt:
  enabled: true
  issuer: "https://auth.example.com"
  audience: "playcity-bridge"
  jwksUrl: "https://auth.example.com/.well-known/jwks.json"
  jwksFile: ""
  algorithm: "RS256"
  requiredType: "at+jwt"
  scopeClaim: "scope"
  clientIdClaim: "client_id"
  actionScopePrefix: "bridge:action:"
  eventScopePrefix: "bridge:event:"
  allowedActions:
    - "minecraft.player_status"
    - "minecraft.teleport_player"
  allowedEvents:
    - "minecraft.player_joined"
    - "minecraft.player_left"
  requestsPerMinute: 120
  maxConcurrent: 4
  maxEventConnections: 2
  maxLifetimeSeconds: 3600
```

Configure exactly one of `jwksUrl` and `jwksFile`. A local JWKS file must be a regular non-symlink file under `plugins/Bridge` and is bounded to 256 KiB. A remote JWKS must use HTTPS except for an explicitly enabled loopback development endpoint. Nimbus caches, rate-limits and retries remote key retrieval; a temporary key-source failure returns `jwt_verification_unavailable` rather than accepting an unverifiable token.

## Operational guidance

- Issue short-lived access tokens and rotate signing keys with distinct `kid` values.
- Keep Bridge behind a private network or TLS reverse proxy; a signed token is not encrypted.
- Retain static service credentials for small private deployments and recovery, but do not reuse them as JWT signing keys.
- Define revocation outside Bridge. Short expiration limits exposure; Bridge does not currently perform token introspection or maintain a `jti` denylist.
- Do not place tokens in WebSocket query strings. Backend clients must use the Authorization header.
- Do not give browser code direct Bridge credentials. Product authorization and account linking remain in the calling backend.
