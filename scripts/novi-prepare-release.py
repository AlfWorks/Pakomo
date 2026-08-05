#!/usr/bin/env python3
"""Create and sign a Novi v1 manifest using Python stdlib and OpenSSL."""

import argparse
import base64
import hashlib
import json
import pathlib
import subprocess


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=pathlib.Path)
    parser.add_argument("--application-id", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--artifact-path", required=True)
    parser.add_argument("--key-id", required=True)
    parser.add_argument("--private-key", required=True, type=pathlib.Path)
    parser.add_argument("--openssl", default="openssl", help="OpenSSL executable")
    parser.add_argument("--changelog-file", type=pathlib.Path)
    parser.add_argument("--mandatory", action="store_true")
    parser.add_argument("--output", default="release", type=pathlib.Path)
    args = parser.parse_args()

    if args.version_code < 1:
        parser.error("--version-code must be positive")
    digest = hashlib.sha256()
    with args.apk.open("rb") as apk_file:
        for chunk in iter(lambda: apk_file.read(1024 * 1024), b""):
            digest.update(chunk)
    manifest = {
        "schemaVersion": 1,
        "keyId": args.key_id,
        "applicationId": args.application_id,
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "artifact": {
            "path": args.artifact_path,
            "sizeBytes": args.apk.stat().st_size,
            "sha256": digest.hexdigest(),
        },
        "mandatory": args.mandatory,
        "changelog": args.changelog_file.read_text(encoding="utf-8").strip() if args.changelog_file else None,
    }
    args.output.mkdir(parents=True, exist_ok=True)
    manifest_path = args.output / "latest.json"
    signature_path = args.output / "latest.json.sig"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    completed = subprocess.run(
        [args.openssl, "dgst", "-sha256", "-sign", str(args.private_key), str(manifest_path)],
        check=True,
        capture_output=True,
    )
    signature_path.write_text(base64.b64encode(completed.stdout).decode("ascii") + "\n", encoding="ascii", newline="\n")
    print(manifest_path)
    print(signature_path)


if __name__ == "__main__":
    main()
