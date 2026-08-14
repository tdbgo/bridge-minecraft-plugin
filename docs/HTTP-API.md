# Bridge HTTP API

Bridge exposes a small server-to-server JSON API. It should normally listen on loopback and be reached through an HTTPS reverse proxy, VPN or private service network.

## Authentication

Action endpoints accept either:

```http
Authorization: Bearer <client-token>
```

```http
X-Bridge-Token: <client-token>
```

The Bearer value may be a configured static client token or a signed JWT access token. Static tokens select one configured client identity. JWTs must pass the pinned issuer, audience, type, algorithm and JWKS checks; their action scopes are intersected with the local JWT action allowlist. Bridge then enforces request-rate and concurrency limits. Metrics use a separate static credential and do not accept action JWTs.

See [JWT-AUTH.md](JWT-AUTH.md) for the signed-token contract. `X-Bridge-Token` is intended for static credentials; JWT clients should use the standard Authorization header.

## Endpoints

### `GET /v1/health`

Unauthenticated liveness and configuration readiness. It does not enumerate action names. Relay installations also expose the bounded event queue depth and `relayEventGap`; a true gap requires the receiver to rebuild authoritative state.

### `GET /v1/actions`

Returns action descriptors visible to the authenticated client. Each descriptor contains `name`, `version`, `description` and `risk`.

### `POST /v1/actions`

Executes one typed action. The JSON object must include:

- `requestId`: 1-160 characters matching `[A-Za-z0-9_.:-]+`
- `action`: registered action name
- action-specific fields

Bridge treats the entire object except `requestId` and `action` as the action payload for idempotency. Reusing an ID with different content returns `409 idempotency_conflict`.

Successful response:

```json
{
  "ok": true,
  "status": "succeeded",
  "code": "ok",
  "message": "OK",
  "requestId": "example.1",
  "data": {}
}
```

Failure responses use the same envelope with `ok: false`, `status: failed` and a stable code. Common gateway codes include `unauthorized`, `jwt_verification_unavailable`, `jwt_identity_capacity_exceeded`, `action_forbidden`, `rate_limited`, `too_many_concurrent_requests`, `invalid_request_id`, `unknown_action`, `idempotency_conflict`, `action_timeout` and `action_failed`.

An HTTP timeout does not cancel already scheduled Bukkit work. Retry only with the same request ID.

### `GET /v1/status` and `GET /metrics`

Optional aggregate observability endpoints. They require the separate metrics credential through `Authorization: Bearer` or `X-Bridge-Metrics-Token`.

## Exposure rules

- Do not proxy Bridge directly to browsers.
- Do not place product login or account-linking logic in Bridge.
- Terminate TLS before traffic crosses an untrusted network.
- Give each backend a separate token file and the smallest possible action list.
- Keep mutating actions disabled until their world and product policies are configured.
