# Security policy

## Supported versions

Bridge has not published a stable release yet. Security fixes are prepared for the newest maintained `0.5.x` prerelease line; older development snapshots are not supported.

## Reporting a vulnerability

After the public repository is created, report vulnerabilities through its private GitHub Security Advisory form under **Security > Advisories > New draft security advisory**. Do not disclose a suspected vulnerability in a public issue before maintainers have had an opportunity to investigate it.

Include the affected Bridge version, Paper and Java versions, configuration shape with all credentials removed, reproduction steps and expected security boundary. Never include bearer tokens, JWTs, relay identity files, private keys, pairing credentials, player data or private infrastructure addresses.

Use ordinary public issues for non-sensitive defects and feature requests only. No response-time commitment is made before a public maintenance process is established.

## Security boundary

Bridge providers are trusted code installed inside the Paper process. Reports about a provider plugin intentionally executing arbitrary Java code are generally outside Bridge's boundary. Authentication bypasses, scope expansion, unsafe remote action execution, credential disclosure, replay errors and unbounded remote-resource consumption are in scope.
