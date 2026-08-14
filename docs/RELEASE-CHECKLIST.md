# Release checklist

## Release decisions

- Bridge source, binaries and `bridge-api` use Apache License 2.0.
- The first public candidate is `0.5.0-rc.1`, dated 2026-08-14.
- A stable `0.5.0` remains blocked on the manual Paper gate below.
- The Modrinth project is reserved as the private project `bridge-by-playcity-block` under `cityofseoul`; its description and Apache-2.0 license are configured.
- The remaining publication setup is to create the Git repository and public remote, then enable private security advisories.

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
4. For `0.5.0-rc.1`, select version type **Beta**, loader **Paper**, game version **26.2**, and server-side-only compatibility. Do not claim Folia, Bukkit or Spigot compatibility.
5. Complete the manual Paper gate before creating the Modrinth version. Creating the version uploads the JAR and unlocks project tags and content disclosures.
6. Review the resulting project page, tags, disclosures and file hash before submitting the project for moderation.

Keep the Modrinth project private until the version, tags and disclosures have been reviewed together. An uploaded release candidate is not a stable release.

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
