# `minecraft.player_status`

This built-in read-only action returns authoritative online state for one player UUID. It never falls back from a supplied UUID to a username.

Request:

```json
{
  "requestId": "example.status.1",
  "action": "minecraft.player_status",
  "source": "example.backend",
  "targetPlayer": {
    "uuid": "00000000-0000-0000-0000-000000000000",
    "username": "OptionalDiagnosticName"
  }
}
```

Allowed fields are `requestId`, `action`, optional `source` and `targetPlayer`. The target object accepts the required canonical UUID and an optional non-blank username. Additional fields are rejected.

Offline is a successful query result:

```json
{"online": false}
```

Online result data includes the authoritative UUID, current username and current world:

```json
{
  "online": true,
  "player": {
    "uuid": "00000000-0000-0000-0000-000000000000",
    "username": "CurrentName",
    "world": "world"
  }
}
```

Generate a new request ID for each fresh status observation. Reuse an ID only when retrying the exact same observation request because Bridge may replay its cached result.
