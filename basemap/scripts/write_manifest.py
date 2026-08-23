#!/usr/bin/env python3
"""Write the retained, machine-readable basemap build manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--source-report", type=Path, required=True)
    parser.add_argument("--validation-report", type=Path, required=True)
    parser.add_argument("--build-id", required=True)
    parser.add_argument("--config-sha256", required=True)
    parser.add_argument("--source-date", required=True)
    parser.add_argument("--source-url", required=True)
    parser.add_argument("--source-checksum-url", required=True)
    parser.add_argument("--planetiler-version", required=True)
    parser.add_argument("--planetiler-commit", required=True)
    parser.add_argument("--planetiler-image", required=True)
    parser.add_argument("--pmtiles-version", required=True)
    parser.add_argument("--pmtiles-commit", required=True)
    parser.add_argument("--command", required=True)
    args = parser.parse_args()

    bundle = args.bundle.resolve()
    archive = bundle / "serbia.pmtiles"
    source = json.loads(args.source_report.read_text("utf-8"))
    validation = json.loads(args.validation_report.read_text("utf-8"))
    excluded = {"build-manifest.json", "serbia.pmtiles"}
    files = []
    for path in sorted((item for item in bundle.rglob("*") if item.is_file()), key=str):
        relative = path.relative_to(bundle).as_posix()
        if relative in excluded:
            continue
        files.append(
            {"path": relative, "sizeBytes": path.stat().st_size, "sha256": sha256_file(path)}
        )

    manifest = {
        "schemaVersion": 1,
        "buildId": args.build_id,
        "commandConfigSha256": args.config_sha256,
        "command": args.command,
        "source": {
            **source,
            "date": args.source_date,
            "url": args.source_url,
            "checksumUrl": args.source_checksum_url,
            "license": "ODbL-1.0",
        },
        "toolchain": {
            "applicationJava": 17,
            "buildJava": "21 (inside pinned Planetiler image)",
            "planetiler": {
                "version": args.planetiler_version,
                "commit": args.planetiler_commit,
                "image": args.planetiler_image,
            },
            "goPmtiles": {"version": args.pmtiles_version, "commit": args.pmtiles_commit},
        },
        "artifact": {
            "filename": archive.name,
            "format": "PMTiles v3 / gzip MVT",
            "sizeBytes": archive.stat().st_size,
            "sha256": sha256_file(archive),
        },
        "validation": {
            "bounds": validation["header"]["bounds"],
            "minzoom": validation["header"]["minzoom"],
            "maxzoom": validation["header"]["maxzoom"],
            "layers": validation["layers"],
            "metadataSha256": validation["metadataSha256"],
            "smokeTiles": validation["smokeTiles"],
            "style": validation["style"],
            "archiveStructuralVerification": validation["archiveStructuralVerification"],
        },
        "attribution": {
            "requiredText": "© OpenStreetMap contributors",
            "copyrightUrl": "https://www.openstreetmap.org/copyright",
            "databaseLicense": "ODbL-1.0",
            "visibleInStyle": True,
            "notice": "THIRD_PARTY_NOTICES.md",
        },
        "bundleFiles": files,
    }
    target = bundle / "build-manifest.json"
    target.write_text(
        json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
