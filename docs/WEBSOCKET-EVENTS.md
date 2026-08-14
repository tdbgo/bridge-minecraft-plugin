# WebSocket event protocol

Bridge 0.5 provides an optional event-only WebSocket endpoint. It does not execute actions or expose a console. HTTP remains the command and query transport.

## Endpoint and authentication

The default listener is disabled and, when enabled, binds `127.0.0.1:25586`. Connect to `/v1/events` and send the same `Authorization: Bearer <credential>` header used for HTTP. Backend WebSocket clients can set this header; ordinary browser code normally cannot, so browsers should connect through an authenticated application backend.

Use a TLS reverse proxy or private network for remote access. Bridge's WebSocket listener does not terminate TLS. Static clients require explicit `allowedEvents` and `maxEventConnections`. A client with only `allowedActions` cannot receive events.

## Server hello

After authentication Bridge sends:

```json
{
  "type": "hello",
  "protocolVersion": 1,
  "instanceId": "86bb...",
  "bridgeVersion": "<version>",
  "events": [
    {
      "name": "minecraft.player_joined",
      "version": "1",
      "description": "Published after one player joins this Paper server.",
      "sensitivity": "player"
    }
  ]
}
```

Only descriptors permitted by the authenticated event scope are disclosed.

## Subscribe and replay

Subscribe to exact names or locally permitted prefix rules:

```json
{
  "type": "subscribe",
  "events": ["minecraft.player_joined", "minecraft.player_left"],
  "instanceId": "86bb...",
  "afterSequence": 120
}
```

Omit `instanceId` and `afterSequence` for a new live-only subscription. A reconnecting client sends the last accepted `instanceId` and sequence. Bridge acknowledges the subscription, replays retained events in sequence order, then continues with live events.

Replay is an in-memory convenience, not durable messaging. `event_instance_changed` means Bridge restarted; the consumer must rebuild state through an authoritative HTTP query. `event_replay_gap` means the cursor is older than the bounded replay window. `event_replay_too_large` means the retained range exceeds one configured replay batch; Bridge refuses to truncate it silently. None of these conditions permits the client to assume missed state.

## Event envelope

```json
{
  "type": "event",
  "instanceId": "86bb...",
  "sequence": 121,
  "event": "minecraft.player_left",
  "version": "1",
  "publishedAt": 1786700000000,
  "data": {
    "playerUuid": "00000000-0000-0000-0000-000000000000"
  }
}
```

Built-in player presence events contain only the UUID. Product code must still use `minecraft.player_status` when it needs an authoritative current answer after reconnecting or observing stale UI state.

## Limits and closure

- Client messages, pending outbound messages, replay storage and replay batches are bounded.
- Java-WebSocket heartbeat detection closes lost connections.
- A slow consumer is closed with code `1013` instead of accumulating an unbounded queue.
- Invalid authentication or an excessive subscription closes with policy code `1008` or returns a typed error.
- Binary messages and WebSocket action execution are not supported.

Providers register event descriptors through `bridge-api` and publish immutable JSON-compatible maps. They must snapshot Bukkit state on the proper server thread before publishing.
