# Release checklist

## Release decisions

- Bridge source, binaries and `bridge-api` use Apache License 2.0.
- The current public candidate is `0.5.0-rc.2`, dated 2026-08-15.
- A stable `0.5.0` remains blocked on the manual Paper gate below.
- Source is published at `https://github.com/tdbgo/bridge-minecraft-plugin`.
- The Modrinth project is `bridge-by-playcity-block` under `cityofseoul`; the `0.5.0-rc.2` Paper artifact has been uploaded.

The `verifyReleaseReadiness` Gradle task checks the semantic version, dated changelog entry, Apache license, NOTICE and `bridge-api` POM metadata.

## Automated gate

1. Confirm that the changelog contains `## <version> - YYYY-MM-DD`.
2. Run `./gradlew clean assembleRelease -PbridgeVersion=<version> --console=plain`.
3. Run `python ops/verify-release-artifacts.py --plugin build/release/Bridge-<version>.jar --api build/release/bridge-api-<version>.jar --expect-version <version>`.
4. Run the public-surface audit and review every candidate in context.
5. Verify that `build/release/SHA256SUMS` matches every staged JAR and ZIP.

Pushing a `v<version>` tag runs the same release gate, generates provenance attestations and creates a GitHub release only after every check succeeds.

Publish the Git public surface or the generated release bundle. Do not archive the raw working directory: ignored local operator handoffs and staging files are intentionally outside the reusable plugin surface.

## Modrinth gate

1. Use only `build/release/Bridge-<version>.jar` as the primary Modrinth file. Do not upload the raw workspace, release ZIP, developer API JAR, Javadocs, test reports or operator handoffs.
2. Verify the plugin JAR with `ops/verify-release-artifacts.py` and compare its SHA-256 against `build/release/SHA256SUMS` immediately before upload.
3. Use the matching `CHANGELOG.md` section as the version release notes and record the selected metadata during review.
4. For `0.5.0-rc.2`, select version type **Beta**, loader **Paper**, game version **26.2**, and server-side-only compatibility. Do not claim Folia, Bukkit or Spigot compatibility.
5. Complete the runtime checks appropriate to the release type before publishing the Modrinth version. A release candidate remains **Beta** while any stable-release gate is incomplete.
6. Review the resulting project page, tags, disclosures and file hash before submitting it for moderation or making it public.

Review the version, tags and disclosures together before making a release candidate public. An uploaded release candidate is not a stable release.

## Manual Paper gate

This work is intentionally not covered by the current automated preparation. Before declaring a stable release, start a disposable Paper 26.2 server on Java 25 and verify:

- first startup with the default configuration;
- scoped REST discovery and one read-only action;
- WebSocket authentication, subscribe, reconnect replay and slow-consumer closure;
- JWT validation with a disposable issuer/JWKS;
- relay enrollment, action result and event acknowledgement against a disposable receiver;
- normal shutdown without executor or socket leaks;
- disable and re-enable through a full server restart, never a hot-reload tool.

Record the Paper build, Java build, Bridge JAR SHA-256 and observed results in the release notes.
