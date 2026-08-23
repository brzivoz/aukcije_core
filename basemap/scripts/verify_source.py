#!/usr/bin/env python3
"""Fail-closed verification for the dated Geofabrik OSM PBF input."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


MD5_LINE = re.compile(r"^([0-9a-fA-F]{32})\s+[ *]?([^/\\]+)$")


def file_hash(path: Path, algorithm: str) -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify(
    pbf: Path,
    checksum: Path,
    expected_name: str,
    expected_md5: str,
    expected_sha256: str,
    expected_size: int,
) -> dict[str, object]:
    if not pbf.is_file():
        raise ValueError(f"source PBF is missing: {pbf}")
    if not checksum.is_file():
        raise ValueError(f"source checksum is missing: {checksum}")
    if pbf.name != expected_name:
        raise ValueError(f"source filename mismatch: expected {expected_name}, got {pbf.name}")

    nonempty = [line.strip() for line in checksum.read_text("utf-8").splitlines() if line.strip()]
    if len(nonempty) != 1:
        raise ValueError("Geofabrik checksum must contain exactly one non-empty line")
    match = MD5_LINE.fullmatch(nonempty[0])
    if not match:
        raise ValueError("Geofabrik checksum has an invalid MD5 line")
    remote_md5, remote_name = match.groups()
    remote_md5 = remote_md5.lower()
    if remote_name != expected_name:
        raise ValueError(f"checksum filename mismatch: expected {expected_name}, got {remote_name}")
    if remote_md5 != expected_md5.lower():
        raise ValueError("downloaded Geofabrik checksum differs from the reviewed MD5 pin")

    actual_size = pbf.stat().st_size
    if actual_size != expected_size:
        raise ValueError(f"source size mismatch: expected {expected_size}, got {actual_size}")
    actual_md5 = file_hash(pbf, "md5")
    if actual_md5 != remote_md5:
        raise ValueError("source PBF does not match the downloaded Geofabrik MD5")
    actual_sha256 = file_hash(pbf, "sha256")
    if actual_sha256 != expected_sha256.lower():
        raise ValueError("source PBF does not match the reviewed SHA-256 pin")

    return {
        "filename": expected_name,
        "sizeBytes": actual_size,
        "md5": actual_md5,
        "sha256": actual_sha256,
        "checksumFilename": checksum.name,
        "checksumMd5": remote_md5,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pbf", type=Path, required=True)
    parser.add_argument("--checksum", type=Path, required=True)
    parser.add_argument("--expected-name", required=True)
    parser.add_argument("--expected-md5", required=True)
    parser.add_argument("--expected-sha256", required=True)
    parser.add_argument("--expected-size", type=int, required=True)
    args = parser.parse_args()
    try:
        report = verify(
            args.pbf,
            args.checksum,
            args.expected_name,
            args.expected_md5,
            args.expected_sha256,
            args.expected_size,
        )
    except (OSError, UnicodeError, ValueError) as error:
        parser.error(str(error))
    print(json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
