# Outbound relay protocol

The optional relay transport lets a Minecraft host receive actions while making only outbound HTTPS connections. Any web service can implement this protocol; Bridge does not require a specific vendor endpoint.

The relay is disabled on fresh installs. Configure an HTTPS `relay.baseUrl`, server name and explicit `relay.allowedActions` before enabling it. Plain HTTP is accepted only for an explicitly enabled loopback development endpoint.

## Common request fields

Bridge sends JSON with:

- `protocolVersion`: currently `1`
- `agentId`: locally generated UUID
- `bridgeVersion`
- `serverVersion`
- `capabilities`: registered action names
- `eventCapabilities`: registered event names permitted by `relay.allowedEvents`

Requests use `Authorization: Bearer <agent-credential>` and `X-Bridge-Agent-Id`. The credential is generated and stored in the Bridge data directory; it must never be logged or shown as a pairing code.

## `POST {baseUrl}/enroll`

Adds `serverName` to the common envelope. The service returns either an approved state or a short-lived human pairing code:

```json
{"state":"pending","pairingCode":"AB12CD34","pairingExpiresAt":"2026-08-14T12:30:00Z"}
```

```json
{"state":"approved"}
```

The web implementation is responsible for authenticated approval, expiry, revocation and rate limiting. Store only a suitable verifier or encrypted credential according to the web service's threat model.

## `POST {baseUrl}/poll`

Bridge long-polls with the common envelope plus optional aggregate `status`, bounded Prometheus `metrics` and one bounded event batch. A response contains no action or one leased action:

```json
{
  "action": {
    "leaseId": "lease-1",
    "request": {
      "requestId": "example.1",
      "action": "example:action"
    }
  }
}
```

The web service must lease each action to one agent at a time and preserve the caller's stable request ID across retries.

When `relay.allowedEvents` is non-empty, the request may also contain:

```json
{
  "events": {
    "instanceId": "86bb...",
    "overflowed": false,
    "droppedThroughSequence": 0,
    "items": [
      {
        "sequence": 121,
        "event": "minecraft.player_left",
        "version": "1",
        "publishedAt": 1786700000000,
        "data": {
          "playerUuid": "00000000-0000-0000-0000-000000000000"
        }
      }
    ]
  }
}
```

The receiver acknowledges only events it has durably accepted:

```json
{
  "action": null,
  "eventAck": {
    "instanceId": "86bb...",
    "throughSequence": 121,
    "acceptGap": false
  }
}
```

Bridge retains unacknowledged items and may send them again, so the receiver must deduplicate by `(agentId, instanceId, sequence)`. Event storage is memory-bounded. If `overflowed` is true, events through `droppedThroughSequence` were lost; the receiver must rebuild authoritative state before replying with `acceptGap: true`. An ACK for another instance or for a sequence beyond the transmitted batch is rejected.

Relay event delivery survives transient HTTP failures but not a Bridge process restart. A new `instanceId` always creates a new sequence domain. An operator-triggered `/bridge reload` preserves the Bridge instance but deliberately reports an overflow gap on the next relay event because the previous transport queue cannot be proven complete. This transport is appropriate for typed operational events; it is not durable telemetry storage.

## `POST {baseUrl}/result`

Bridge returns `protocolVersion`, `leaseId`, `requestId`, `httpStatus` and the standard action result envelope. The service acknowledges accepted results with HTTP 200.

Bridge durably records a relay request before executing it. After an interrupted unknown outcome it reports `action_outcome_unknown` instead of repeating the action automatically.

## Web implementation responsibilities

- Require HTTPS and authenticate every request.
- Pair and revoke agents through an authenticated administrator flow.
- Enforce tenant/server ownership and action policy before leasing.
- Bound queues, leases, response bodies and retention.
- Deduplicate event batches and acknowledge only durably accepted sequences.
- Treat `overflowed` or an instance change as a state-rebuild boundary.
- Never invent a new request ID when retrying the same operation.
- Treat Bridge's local `relay.allowedActions` as a second, independent policy boundary.
- Treat `relay.allowedEvents` as a separate deny-by-default event policy.
