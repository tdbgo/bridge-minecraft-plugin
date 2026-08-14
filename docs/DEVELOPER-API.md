# Bridge developer API

The `bridge-api` module is the compile-only contract for plugins that provide typed actions and events. The runtime implementation is exposed through Bukkit `ServicesManager`.

## Dependency and loading

Publish the API to your local Maven repository while developing:

```text
.\gradlew.bat :bridge-api:publishToMavenLocal
```

Then use `compileOnly("com.playcity:bridge-api:<version>")` and declare Bridge as a required plugin dependency. Use a soft dependency only when your plugin remains fully functional without its Bridge integration. Do not shade the Bridge API into a provider plugin.

## Registration

Register with an owning `Plugin`, immutable `BridgeActionDescriptor` and `BridgeAction`. Names should use a stable namespace such as `myplugin:render_map` or `myplugin.render_map`. Descriptor versions identify the action contract, not the provider JAR version.

Risk classifications:

- `READ_ONLY`: no externally visible server mutation
- `MUTATING`: bounded, intentional state change
- `DESTRUCTIVE`: deletion, bulk replacement or another operation needing stronger operator review

Registration fails on a duplicate name. The returned `BridgeRegistration` can remove only that registration. Bridge also removes all registrations owned by a plugin when it is disabled.

## Events

Register a stable event descriptor and retain the owned publisher:

```java
BridgeEventPublisher publisher = bridge.registerEvent(
    this,
    new BridgeEventDescriptor(
        "example:job_completed",
        "1",
        "Published after one example job reaches a terminal state.",
        BridgeEventSensitivity.GENERAL
    )
);

publisher.publish(Map.of("operationId", operationId, "status", "completed"));
```

Event data follows the same immutable JSON-compatible contract as action data. Publishing is transport-neutral and non-blocking with respect to network delivery. Bridge rejects event data beyond the configured message limit before it enters replay storage. Bridge assigns a process-instance ID, sequence and timestamp. A publisher becomes inactive when closed or when its owning plugin is disabled.

Use events for facts that already happened, not commands. Give requested and completed states distinct names or fields. Do not publish credentials, arbitrary plugin logs, unbounded messages or player data without a specific event scope and retention policy.

## Threading

An action may begin on an HTTP or relay worker thread. It must not access worlds, players, entities, inventories or other Bukkit state directly from that thread. Use `BridgeActionContext.callSync` for short bounded Paper operations. Event publishers may be called from any thread, but providers must first snapshot Bukkit state on the correct server thread.

Database, HTTP, DNS, compression and large pure calculations should stay asynchronous. Do not block the Paper thread while waiting for them. Folia is not currently supported by this context API.

## Data contract

`BridgeActionRequest.payload()` and `BridgeActionResult.data()` are deeply immutable JSON-compatible values:

- object: `Map<String, Object>`
- array: `List<Object>`
- scalar: `String`, finite `Number`, `Boolean` or `null`

The public API deliberately does not expose Gson or another serializer. Providers should reject unsupported fields and validate all values before scheduling Bukkit work.

## Results and failures

Return `BridgeActionResult.success(data)` only after externally visible work has succeeded. Failure codes must be stable lowercase identifiers. Do not return stack traces, credentials or private configuration in messages.

Long-running or destructive workflows should use separate preview/apply actions, durable operation IDs and provider-owned recovery logic. Bridge guarantees that one in-flight request ID is not expired into a second local execution, but it cannot roll back provider side effects.
