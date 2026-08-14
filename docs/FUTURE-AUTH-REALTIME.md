# Real-time and identity roadmap

Bridge 0.5 adds an opt-in WebSocket event endpoint and JWT/JWKS verification. See [WEBSOCKET-EVENTS.md](WEBSOCKET-EVENTS.md) and [JWT-AUTH.md](JWT-AUTH.md) for the implemented contracts. This document now records the product rationale and remaining extensions rather than claiming those capabilities are absent.

## Why WebSocket was added

HTTP actions are a good fit for bounded request/response operations such as checking one linked player, moving that player, sending a notice or starting a synchronization. WebSocket becomes useful when the server needs to publish events without every backend polling it.

Potential PLAYCITY uses include:

- **Maps:** update a linked player's online state immediately, report a teleport request's lifecycle, or invalidate an availability indicator after the player disconnects.
- **BLOCK Admin:** show Notice synchronization progress and completion, notice-delivery acknowledgements, Bridge connectivity changes and action-provider availability in real time.
- **Operations:** update a live server overview when Bridge becomes degraded or a relay reconnects. Prometheus should remain the durable monitoring source; WebSocket would improve interactive UI latency rather than replace metrics.
- **Outbound connectivity:** the current relay carries acknowledged event batches through the existing server-initiated long poll, allowing NAT-bound servers to publish events without opening an inbound listener. A future server-initiated WebSocket remains a latency optimization, not a protocol requirement.

REST should remain the command path even after WebSocket is introduced. WebSocket should primarily carry typed events and acknowledgements, not raw console output, unrestricted chat, or arbitrary commands.

The current protocol implements topic-level scopes, sequence IDs, instance-bound replay cursors, bounded queues, backpressure, heartbeat timeouts and payload-size limits. Replay is intentionally memory-bounded and does not claim durable delivery across a Bridge restart. The same event must not silently mean both "requested" and "completed."

## Why JWT was added

Bridge currently uses a different long random Bearer token for each trusted backend. That model is simple and appropriate for a small number of server-to-server connections. JWT becomes useful when credentials need to be short-lived and issued centrally for many servers, services or operator identities.

Potential PLAYCITY uses include:

- **Maps:** a trusted backend could obtain a short-lived token restricted to `minecraft.player_status` and `minecraft.teleport_player`, one server audience and the linked account. A leaked token would expire quickly and could not call Notice actions.
- **BLOCK Admin:** an operator workflow could receive a token restricted to `minecraft.notice_send` or `minecraft.notice_sync` on selected servers. The subject and token ID could be included in audit records without sharing one permanent administrator secret.
- **Multi-server operations:** every Bridge instance could validate tokens from one trusted issuer using published verification keys. Adding a server would not require copying every web service's shared token to that server.
- **Provider integrations:** a service could receive only the namespace belonging to its plugin, such as `transport.*`, while Bridge continues to enforce its local action allowlist as a second policy boundary.

JWT is only a signed token format. It does not by itself provide login, account linking, authorization policy, immediate revocation or secure browser storage. Bridge implements strict issuer and audience validation, short-lived-token claims, JWKS key rotation support, algorithm and token-type pinning, and bounded clock skew. PLAYCITY still needs a trusted issuer, short expiration policy and revocation strategy. Browser clients should normally call a PLAYCITY backend rather than connect directly to Bridge.

Local configuration must remain authoritative. A JWT scope should be intersected with the server's configured action allowlist, never used to expand it. Static service tokens should remain available for simple private deployments and recovery paths.

## Remaining work for PLAYCITY

1. Keep HTTP actions as the stable mutation and query contract.
2. Add product-specific typed events only when Maps or BLOCK Admin has a concrete consumer and data-retention rule.
3. Integrate JWT with a central PLAYCITY issuer; do not build a Bridge-specific login system.
4. Deploy and validate a web-side relay receiver for acknowledged event batches; consider a server-initiated WebSocket only if long-poll latency becomes material.
5. Preserve the existing action scope, event scope, rate limit, concurrency limit, idempotency and audit boundaries for every new transport and credential type.

WebSocket and JWT solve different problems: WebSocket provides a real-time transport, while JWT provides portable short-lived identity and authorization claims. Supporting one does not require weakening or replacing Bridge's current safety model.
