#!/usr/bin/env python3
"""Hash build-affecting files with their repository-relative names."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--value", action="append", default=[])
    parser.add_argument("files", nargs="+", type=Path)
    args = parser.parse_args()

    root = args.root.resolve()
    digest = hashlib.sha256()
    for path in sorted((item.resolve() for item in args.files), key=str):
        relative = path.relative_to(root).as_posix()
        data = path.read_bytes()
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(str(len(data)).encode("ascii"))
        digest.update(b"\0")
        digest.update(data)
        digest.update(b"\0")
    for value in args.value:
        encoded = value.encode("utf-8")
        digest.update(b"value\0")
        digest.update(str(len(encoded)).encode("ascii"))
        digest.update(b"\0")
        digest.update(encoded)
        digest.update(b"\0")
    print(digest.hexdigest())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
