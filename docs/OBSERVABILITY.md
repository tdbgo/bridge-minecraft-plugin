# Observability

Bridge can expose a bounded aggregate status snapshot and Prometheus text metrics. Observability is disabled on fresh installations.

Enable it with a credential distinct from every action client:

```yaml
metrics:
  enabled: true
  namespace: "bridge"
  token: "CHANGE_ME"
  tokenFile: "secrets/metrics.token"
  sampleIntervalTicks: 100
  staleAfterSeconds: 30
  includedWorlds: []
```

Configure either a usable inline token or `tokenFile`, never both. A token file must contain at least 32 UTF-8 bytes. On POSIX systems Bridge rejects token files with unsafe group/other access.

`GET /v1/status` returns the latest immutable Paper snapshot. `GET /metrics` returns equivalent Prometheus exposition plus Bridge action counters and duration sums. Both accept the metrics token through `Authorization: Bearer` or `X-Bridge-Metrics-Token`.

Paper data is sampled on the primary server thread. HTTP workers only serialize the latest snapshot and never query Bukkit world state directly.

## Privacy boundary

The built-in snapshot contains aggregate server and loaded-world values. It does not contain usernames, UUIDs, addresses, coordinates, messages, request IDs, inventories or arbitrary logs.

Per-world labels contain world names. Use `metrics.includedWorlds` when world names themselves are sensitive or high-cardinality.

## Metrics

Names begin with the configured namespace. A fresh installation uses `bridge_`; an existing configuration without `metrics.namespace` retains the legacy `playcity_` prefix.

The suffixes are:

- `bridge_info`, `bridge_up`, `bridge_metrics_ready`
- `minecraft_info`, `minecraft_start_time_seconds`
- `minecraft_metrics_last_sample_timestamp_seconds`, `minecraft_metrics_stale`
- `minecraft_tps`, `minecraft_tick_duration_seconds`
- `minecraft_players_online`, `minecraft_players_max`
- `minecraft_worlds_loaded`, `minecraft_world_loaded_chunks`
- `minecraft_world_entities`, `minecraft_world_players`
- `bridge_actions_total`
- `bridge_action_duration_seconds_count`, `bridge_action_duration_seconds_sum`

An old snapshot remains readable and is marked stale rather than being discarded. Alert on scrape failure or the stale metric separately from TPS and tick-duration degradation.

Bridge does not provide metrics history, host CPU/memory/disk telemetry, logs, process control or backups. Use an external collector for retention and alerting.
