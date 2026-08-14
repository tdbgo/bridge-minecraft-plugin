#!/usr/bin/env python3
import argparse
import hashlib
import json
import pathlib
import re
import sys
import zipfile


def descriptor_fields(raw: str) -> dict[str, str]:
    fields: dict[str, str] = {}
    for line in raw.splitlines():
        if line.startswith((" ", "\t", "#")) or ":" not in line:
            continue
        key, value = line.split(":", 1)
        fields[key.strip()] = value.strip().strip('"\'')
    return fields


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def inspect_plugin(path: pathlib.Path, expected_version: str) -> dict[str, object]:
    errors: list[str] = []
    with zipfile.ZipFile(path) as archive:
        corrupt = archive.testzip()
        names = set(archive.namelist())
        if corrupt:
            errors.append(f"corrupt entry: {corrupt}")
        if "plugin.yml" not in names:
            errors.append("plugin.yml is missing")
            descriptor = {}
        else:
            descriptor = descriptor_fields(archive.read("plugin.yml").decode("utf-8"))
        expected = {
            "name": "Bridge",
            "version": expected_version,
            "main": "com.playcity.bridge.BridgePlugin",
            "api-version": "26.2",
        }
        for key, value in expected.items():
            if descriptor.get(key) != value:
                errors.append(f"plugin.yml {key} is {descriptor.get(key)!r}, expected {value!r}")
        if "com/playcity/bridge/BridgePlugin.class" not in names:
            errors.append("main class is missing")
        forbidden_prefixes = (
            "org/java_websocket/",
            "com/nimbusds/",
            "com/google/errorprone/",
            "io/papermc/",
            "org/bukkit/",
        )
        for prefix in forbidden_prefixes:
            if any(name.startswith(prefix) for name in names):
                errors.append(f"forbidden unrelocated or server package: {prefix}")
        for prefix in ("com/playcity/bridge/lib/websocket/", "com/playcity/bridge/lib/nimbus/"):
            if not any(name.startswith(prefix) for name in names):
                errors.append(f"relocated runtime package is missing: {prefix}")
        for notice in (
            "META-INF/LICENSE.md",
            "META-INF/NOTICE",
            "META-INF/LICENSES/MIT-Java-WebSocket.txt",
            "META-INF/LICENSES/Apache-2.0.txt",
            "META-INF/THIRD_PARTY_NOTICES.md",
        ):
            if notice not in names:
                errors.append(f"notice is missing: {notice}")
    return {"path": str(path), "sha256": sha256(path), "errors": errors}


def inspect_api(path: pathlib.Path) -> dict[str, object]:
    errors: list[str] = []
    with zipfile.ZipFile(path) as archive:
        corrupt = archive.testzip()
        names = set(archive.namelist())
        if corrupt:
            errors.append(f"corrupt entry: {corrupt}")
        for required in (
            "com/playcity/bridge/api/BridgeApi.class",
            "com/playcity/bridge/api/BridgeEventPublisher.class",
            "com/playcity/bridge/api/BridgeAction.class",
            "META-INF/LICENSE.md",
            "META-INF/NOTICE",
        ):
            if required not in names:
                errors.append(f"public API class is missing: {required}")
        allowed_prefixes = ("META-INF/", "com/playcity/bridge/api/")
        unexpected = sorted(name for name in names if not name.endswith("/") and not name.startswith(allowed_prefixes))
        if unexpected:
            errors.append("unexpected API JAR entries: " + ", ".join(unexpected[:5]))
    return {"path": str(path), "sha256": sha256(path), "errors": errors}


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify Bridge release JAR boundaries.")
    parser.add_argument("--plugin", required=True, type=pathlib.Path)
    parser.add_argument("--api", required=True, type=pathlib.Path)
    parser.add_argument("--expect-version", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:-SNAPSHOT)?", args.expect_version):
        parser.error("--expect-version is not a supported Bridge version")
    missing = [str(path) for path in (args.plugin, args.api) if not path.is_file()]
    if missing:
        print(json.dumps({"errors": ["missing artifact: " + path for path in missing]}, indent=2))
        return 1
    report = {
        "plugin": inspect_plugin(args.plugin, args.expect_version),
        "api": inspect_api(args.api),
    }
    report["ok"] = not report["plugin"]["errors"] and not report["api"]["errors"]
    print(json.dumps(report, indent=2))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
