# Contributing

Bridge targets Paper 26.2 and Java 25. Use the checked-in Gradle wrapper.

```powershell
.\gradlew.bat clean build --console=plain
```

Keep changes within these boundaries:

- Never add an arbitrary command or console-dispatch action.
- Keep Bukkit world, player and entity access on the Paper primary thread through the action context.
- Keep network, database and filesystem waits off the Paper thread.
- Make new configuration safe for existing files and default-deny remote mutation on fresh installs.
- Add tests for validation, authorization, idempotency and failure behavior.
- Do not place credentials, server data, private hostnames or deployment paths in examples or fixtures.

Public API changes require an explicit compatibility review. Additive changes belong in the current major API line; incompatible signatures require a new major version and a migration document.

Run `./gradlew releaseBundle writeReleaseChecksums` when changing packaging, dependencies, public API or documentation included in releases. The resulting files under `build/release` must pass `ops/verify-release-artifacts.py`.

Report security-sensitive problems through the private route documented in [SECURITY.md](SECURITY.md), not through a public issue.

Unless explicitly marked otherwise, contributions intentionally submitted for inclusion in Bridge are accepted under the project's [Apache License 2.0](LICENSE.md), consistent with section 5 of that license.
