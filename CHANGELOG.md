# Changelog

## 0.5.0-rc.2 - 2026-08-15

- Fixed a WebSocket lock-order deadlock when closing slow consumers after outbound event backpressure.

## 0.5.0-rc.1 - 2026-08-14

- Added a provider-owned, serializer-neutral typed event API to `bridge-api`.
- Added an opt-in WebSocket event endpoint with separate event scopes, connection limits, heartbeat detection, bounded queues, sequence numbers and in-memory replay cursors.
- Added built-in player join and leave events containing only the player UUID.
- Added optional JWT/JWKS verification with pinned token type, signature algorithm, issuer and audience.
- Rejected future-issued JWTs and tokens exceeding the locally configured maximum lifetime.
- Intersected JWT action and event scopes with local server allowlists so signed claims cannot expand server policy.
- Preserved static service tokens and the existing HTTP action contract.
- Extended the outbound relay with separately scoped, bounded event batches, receiver acknowledgements, retry-safe duplicate delivery and explicit overflow-gap reporting.
- Aligned HTTP and relay provider payloads by excluding the transport `requestId` and `action` envelope fields.
- Documented why LILO should remain an optional Bridge integration until Bridge provides latest-value coalescing and location-specific transition semantics.
- Added checksummed, reproducible release bundles, a public-artifact verifier, CI, tag-driven releases and artifact attestations.
- Updated the verified Java 25 build toolchain to Gradle 9.6.1, Shadow 9.6.1, Gson 2.14.0 and JUnit 6.1.0.
- Added compatibility, release, contribution, vulnerability-reporting and third-party provenance documentation.
- Adopted the Apache License 2.0 for Bridge source, binaries and the provider API.
- Added a single-path quick start and clearer in-game/console status output for first-time operators.

Existing version-6 configuration files remain readable. WebSocket and JWT are disabled by default, and existing clients or relay installations receive no event scope unless the corresponding allowlists and connection limits are explicitly configured.
