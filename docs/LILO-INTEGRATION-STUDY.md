# LILO and Bridge integration study

## Decision

LILO should not require Bridge unconditionally at this stage. The recommended near-term integration is an optional `softdepend` relationship in which LILO remains responsible for location sampling and delivery, while it may register a small control surface with Bridge when Bridge is present.

## Why mandatory dependency is premature

LILO 2.4 is a specialised outbound state publisher. It samples Paper state on the server thread, filters players, coalesces each player's newest location, batches HTTP delivery, assigns per-player sequence values, retries state transitions, honours `Retry-After`, bounds its queue and opens a circuit breaker during receiver failure.

Bridge 0.5's WebSocket endpoint and outbound relay solve typed event delivery, including NAT-friendly outbound batches. Their replay and queues are process-local and ordered, but they deliberately report gaps rather than coalescing high-frequency values by player key. Moving raw LILO positions into the current Bridge event channel would therefore still lose LILO's most important latest-location and state-transition semantics.

A mandatory dependency would also couple location tracking availability to Bridge startup, configuration and API compatibility even on a server that only needs LILO's direct receiver. That cost is not justified while Bridge cannot fully replace LILO's transport.

## Safe optional integration now

LILO may compile against `bridge-api` with `softdepend: [Bridge]` and, when the service is present, register bounded administrative actions such as:

- `lilo.status` — read-only diagnostics without exposing secrets or player coordinates
- `lilo.force_resync` — explicitly enabled mutating action
- `lilo.pause` and `lilo.resume` — separately scoped operational actions

These actions should call LILO's own public service boundary. Bridge must not dispatch `/lilo` commands, reflect into implementation classes or become the owner of LILO's scheduler. LILO must unregister through its normal plugin lifecycle, which Bridge also protects with owner-bound registrations.

Direct HTTP location delivery should remain the default. A configuration such as `transport: direct` versus a future `transport: bridge` must select exactly one owner; automatic dual-send or fallback would create duplicate and out-of-order state.

LILO already has a useful seam for this work: `LocationTracker` depends on the `LocationPublisher` interface and can replace its publisher during a controlled reload. A future Bridge-backed publisher can therefore be implemented behind that interface without moving Paper sampling or player policy into Bridge. The integration should be a first-class implementation in the LILO project, not reflection from Bridge into LILO internals.

## Requirements before Bridge can own LILO transport

Reconsider making Bridge mandatory only after Bridge provides all of the following:

1. A keyed latest-value event mode that coalesces locations by `(serverName, playerUuid)`.
2. Preservation of LILO's per-player sequence and offline-state ordering contract.
3. Separate retry policies for replaceable positions and non-replaceable join/offline transitions.
4. Explicit payload privacy policy, batching, queue limits, circuit breaking and receiver diagnostics.
5. Load tests showing that many tracked players cannot starve Bridge actions or other event topics.
6. A migration mode with one active transport and observable receiver acknowledgement.

The first prerequisite, an outbound event path without inbound ports, is implemented by Bridge's relay event batches. It is necessary but not sufficient for raw location transport.

If these conditions are met, the clean ownership model is:

```text
LILO
  owns Paper sampling, tracking policy, position schema and sequence

Bridge
  owns credentials, JWT, connection transport, relay, event scopes and shared observability
```

Even in that model, LILO remains a domain plugin rather than functionality absorbed into Bridge. Servers that install LILO would then require Bridge because of the shared transport contract, not because Bridge has taken over location semantics.

## PLAYCITY service boundary

- Maps location markers use LILO's latest accepted location.
- Current online truth and teleport preconditions use Bridge's authoritative Paper-backed actions.
- Bridge player join/leave events may improve UI latency, but after a reconnect Maps must rebuild state through `minecraft.player_status` rather than treating replay freshness as proof of presence.
- BLOCK Admin uses Bridge for scoped commands, Notice operations and live operational events; it does not consume raw LILO coordinates unless a product requirement explicitly needs them.

This separation avoids turning Bridge into a high-volume telemetry database while still allowing a future common security and transport layer.
