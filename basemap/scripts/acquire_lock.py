#!/usr/bin/env python3
"""Acquire or release a basemap build lock under a crash-released mutex."""

from __future__ import annotations

import argparse
import fcntl
import os
import shutil
import sys
from pathlib import Path


def owner(lock_root: Path) -> tuple[int | None, str]:
    try:
        raw_pid, host = (lock_root / "owner").read_text("utf-8").strip().split("\t", 1)
        return int(raw_pid), host
    except (OSError, UnicodeError, ValueError):
        return None, ""


def process_is_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def validate_paths(data_root: Path, lock_root: Path) -> None:
    if (
        lock_root.parent != data_root
        or not lock_root.name.startswith(".build-")
        or not lock_root.name.endswith(".lock")
    ):
        raise ValueError(f"refusing unexpected basemap lock path: {lock_root}")


def refuse(lock_root: Path, lock_pid: int | None, lock_host: str) -> int:
    print(
        f"Another build holds {lock_root} "
        f"(owner pid={lock_pid if lock_pid is not None else 'unknown'} "
        f"host={lock_host or 'unknown'})",
        file=sys.stderr,
    )
    print(
        "If no build is running, remove that exact lock directory and rerun.",
        file=sys.stderr,
    )
    return 2


def acquire(lock_root: Path, process_pid: int, host: str) -> int:
    try:
        lock_root.mkdir()
    except FileExistsError:
        lock_pid, lock_host = owner(lock_root)
        if (
            lock_pid is not None
            and lock_pid > 0
            and lock_host == host
            and process_is_alive(lock_pid)
        ):
            return refuse(lock_root, lock_pid, lock_host)
        if lock_pid is not None and lock_host and lock_host != host:
            return refuse(lock_root, lock_pid, lock_host)
        if not lock_root.is_dir():
            return refuse(lock_root, lock_pid, lock_host)

        if lock_pid is None:
            print(f"Recovering incomplete basemap build lock: {lock_root}", file=sys.stderr)
        else:
            print(
                f"Recovering stale basemap build lock from dead pid {lock_pid}: "
                f"{lock_root}",
                file=sys.stderr,
            )
        shutil.rmtree(lock_root)
        lock_root.mkdir()

    temporary_owner = lock_root / f".owner.{process_pid}"
    temporary_owner.write_text(f"{process_pid}\t{host}\n", encoding="utf-8")
    os.replace(temporary_owner, lock_root / "owner")
    return 0


def release(lock_root: Path, process_pid: int, host: str) -> int:
    if owner(lock_root) != (process_pid, host):
        return 0
    (lock_root / "owner").unlink(missing_ok=True)
    try:
        lock_root.rmdir()
    except OSError:
        pass
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("acquire", "release"))
    parser.add_argument("--data-root", type=Path, required=True)
    parser.add_argument("--lock-root", type=Path, required=True)
    parser.add_argument("--pid", type=int, required=True)
    parser.add_argument("--host", required=True)
    args = parser.parse_args()

    data_root = Path(os.path.abspath(args.data_root))
    lock_root = Path(os.path.abspath(args.lock_root))
    try:
        validate_paths(data_root, lock_root)
        if args.pid <= 0:
            raise ValueError("basemap lock owner PID must be positive")
        data_root.mkdir(parents=True, exist_ok=True)
        with (data_root / ".build-lock-manager").open("a+", encoding="utf-8") as manager:
            fcntl.flock(manager, fcntl.LOCK_EX)
            if args.action == "acquire":
                return acquire(lock_root, args.pid, args.host)
            return release(lock_root, args.pid, args.host)
    except (OSError, ValueError) as error:
        parser.error(str(error))
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
