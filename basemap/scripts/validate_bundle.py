#!/usr/bin/env python3
"""Validate PMTiles structure, metadata, smoke reads, style, assets, and manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path
from typing import Any


EXPECTED_LAYERS = {
    "boundaries",
    "buildings",
    "landuse",
    "places",
    "pois",
    "roads",
    "water",
}
SMOKE_TILES = ((5, 17, 11), (9, 285, 184), (14, 9123, 5907))
GLYPH_RANGES = (
    "0-255",
    "256-511",
    "512-767",
    "768-1023",
    "1024-1279",
    "8192-8447",
)
FORBIDDEN_ASSET_HOSTS = (
    "tile.openstreetmap.org",
    "protomaps.github.io",
    "unpkg.com",
    "cdn.jsdelivr.net",
)
def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run_tool(pmtiles: Path, *args: str, binary: bool = False) -> bytes | str:
    completed = subprocess.run(
        [str(pmtiles), *args],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if completed.returncode != 0:
        stdout = completed.stdout.decode("utf-8", errors="replace").strip()
        stderr = completed.stderr.decode("utf-8", errors="replace").strip()
        details = "\n".join(part for part in (stdout, stderr) if part)
        raise ValueError(f"pmtiles {' '.join(args)} failed: {details}")
    if binary:
        return completed.stdout
    return completed.stdout.decode("utf-8")


def walk_strings(value: Any, path: tuple[str, ...] = ()):
    if isinstance(value, dict):
        for key, child in value.items():
            yield from walk_strings(child, (*path, str(key)))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from walk_strings(child, (*path, str(index)))
    elif isinstance(value, str):
        yield path, value


def validate_style(bundle: Path, metadata_layers: set[str]) -> dict[str, object]:
    style_path = bundle / "style.json"
    try:
        style = json.loads(style_path.read_text("utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ValueError(f"invalid style.json: {error}") from error
    if style.get("version") != 8:
        raise ValueError("style.json must use MapLibre style specification version 8")
    if style.get("glyphs") != "/basemap/glyphs/{fontstack}/{range}.pbf":
        raise ValueError("style glyph URL must be the reviewed same-origin bundle path")
    if style.get("sprite") != "/basemap/sprites/light":
        raise ValueError("style sprite URL must be the reviewed same-origin bundle path")
    sources = style.get("sources")
    if not isinstance(sources, dict) or set(sources) != {"serbia"}:
        raise ValueError("style must contain exactly one Serbia vector source")
    source = sources["serbia"]
    if source.get("type") != "vector" or source.get("url") != "pmtiles:///basemap/serbia.pmtiles":
        raise ValueError("style source must use the relative local PMTiles URL")
    if "tiles" in source:
        raise ValueError("style must not contain a runtime tile URL template")
    attribution = source.get("attribution", "")
    if "OpenStreetMap contributors" not in attribution or "openstreetmap.org/copyright" not in attribution:
        raise ValueError("style source must contain visible OpenStreetMap attribution")

    for path, value in walk_strings(style):
        lowered = value.lower()
        if any(host in lowered for host in FORBIDDEN_ASSET_HOSTS):
            raise ValueError(f"style contains forbidden runtime host at {'.'.join(path)}")
        if re.search(r"https?://", value) and (not path or path[-1] != "attribution"):
            raise ValueError(f"style contains external runtime URL at {'.'.join(path)}")

    source_layers = {
        layer["source-layer"]
        for layer in style.get("layers", [])
        if isinstance(layer, dict) and "source-layer" in layer
    }
    unknown_layers = source_layers - metadata_layers
    if unknown_layers:
        raise ValueError(f"style references missing PMTiles layers: {sorted(unknown_layers)}")

    font_stacks: set[str] = set()
    referenced_icons: set[str] = set()
    for layer in style.get("layers", []):
        if not isinstance(layer, dict):
            continue
        layout = layer.get("layout", {})
        if not isinstance(layout, dict):
            continue
        text_font = layout.get("text-font")
        if isinstance(text_font, list) and all(isinstance(item, str) for item in text_font):
            font_stacks.update(text_font)
        icon_image = layout.get("icon-image")
        if isinstance(icon_image, str):
            referenced_icons.add(icon_image)
        elif icon_image is not None:
            raise ValueError("style icon-image expressions are not supported by this asset validator")
    if font_stacks != {"Noto Sans Regular"}:
        raise ValueError(f"unexpected or missing style font stack: {sorted(font_stacks)}")
    for font_stack in font_stacks:
        for range_name in GLYPH_RANGES:
            glyph = bundle / "glyphs" / font_stack / f"{range_name}.pbf"
            if not glyph.is_file() or glyph.stat().st_size == 0:
                raise ValueError(f"missing local glyph range: {glyph.relative_to(bundle)}")

    if not referenced_icons:
        raise ValueError("style must reference at least one bundled sprite icon")
    sprite_names: set[str] | None = None
    for scale in ("", "@2x"):
        sprite_json = bundle / "sprites" / f"light{scale}.json"
        sprite_png = bundle / "sprites" / f"light{scale}.png"
        try:
            sprite_index = json.loads(sprite_json.read_text("utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as error:
            raise ValueError(f"invalid sprite index {sprite_json.name}: {error}") from error
        if not isinstance(sprite_index, dict) or not sprite_index:
            raise ValueError(f"sprite index is empty: {sprite_json.name}")
        current_names = set(sprite_index)
        if sprite_names is None:
            sprite_names = current_names
        elif current_names != sprite_names:
            raise ValueError("1x and 2x sprite indexes contain different icon names")
        try:
            png_header = sprite_png.read_bytes()[:8]
        except OSError as error:
            raise ValueError(f"missing sprite image {sprite_png.name}: {error}") from error
        if png_header != b"\x89PNG\r\n\x1a\n":
            raise ValueError(f"invalid PNG sprite image: {sprite_png.name}")
    assert sprite_names is not None
    missing_icons = referenced_icons - sprite_names
    if missing_icons:
        raise ValueError(f"style references missing sprite icons: {sorted(missing_icons)}")

    for notice in (
        bundle / "THIRD_PARTY_NOTICES.md",
        bundle / "licenses" / "Noto-OFL-1.1.txt",
        bundle / "licenses" / "Tangram-Icons-MIT.md",
    ):
        if not notice.is_file() or notice.stat().st_size == 0:
            raise ValueError(f"missing third-party notice: {notice.relative_to(bundle)}")

    return {
        "styleSha256": sha256_file(style_path),
        "sourceLayers": sorted(source_layers),
        "fontStacks": sorted(font_stacks),
        "glyphRanges": list(GLYPH_RANGES),
        "sprite": "/basemap/sprites/light",
        "referencedIcons": sorted(referenced_icons),
        "attributionVisible": True,
        "externalRuntimeAssets": [],
    }


def validate_manifest(
    bundle: Path, report: dict[str, object], expected_command: str
) -> None:
    manifest_path = bundle / "build-manifest.json"
    try:
        manifest = json.loads(manifest_path.read_text("utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ValueError(f"invalid build-manifest.json: {error}") from error
    if manifest.get("schemaVersion") != 1:
        raise ValueError("unsupported build manifest schemaVersion")
    if manifest.get("command") != expected_command:
        raise ValueError("manifest command does not match the normalized build argv")
    artifact = manifest.get("artifact", {})
    archive = bundle / "serbia.pmtiles"
    if artifact.get("filename") != archive.name:
        raise ValueError("manifest artifact filename mismatch")
    if artifact.get("sizeBytes") != archive.stat().st_size:
        raise ValueError("manifest artifact size mismatch")
    if artifact.get("sha256") != sha256_file(archive):
        raise ValueError("manifest artifact SHA-256 mismatch")
    validation = manifest.get("validation", {})
    if validation.get("metadataSha256") != report["metadataSha256"]:
        raise ValueError("manifest metadata SHA-256 mismatch")
    if validation.get("bounds") != report["header"]["bounds"]:
        raise ValueError("manifest bounds mismatch")
    if validation.get("layers") != report["layers"]:
        raise ValueError("manifest layer set mismatch")

    recorded_files = manifest.get("bundleFiles")
    if not isinstance(recorded_files, list) or not recorded_files:
        raise ValueError("manifest bundleFiles is missing")
    recorded_paths: list[str] = []
    for item in recorded_files:
        if not isinstance(item, dict) or not isinstance(item.get("path"), str):
            raise ValueError("manifest bundleFiles entry is malformed")
        relative = Path(item["path"])
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError(f"manifest file path escapes the bundle: {item['path']}")
        recorded_paths.append(relative.as_posix())
        path = bundle / relative
        if not path.is_file():
            raise ValueError(f"manifest file is missing: {item['path']}")
        if path.stat().st_size != item.get("sizeBytes") or sha256_file(path) != item.get("sha256"):
            raise ValueError(f"manifest file hash/size mismatch: {item['path']}")
    if len(recorded_paths) != len(set(recorded_paths)):
        raise ValueError("manifest bundleFiles contains duplicate paths")
    excluded = {"build-manifest.json", "serbia.pmtiles"}
    actual_paths = {
        path.relative_to(bundle).as_posix()
        for path in bundle.rglob("*")
        if path.is_file() and path.relative_to(bundle).as_posix() not in excluded
    }
    recorded_path_set = set(recorded_paths)
    if actual_paths != recorded_path_set:
        unrecorded = sorted(actual_paths - recorded_path_set)
        absent = sorted(recorded_path_set - actual_paths)
        raise ValueError(
            f"manifest inventory mismatch: unrecorded={unrecorded}, absent={absent}"
        )


def validate(args: argparse.Namespace) -> dict[str, object]:
    bundle = args.bundle.resolve()
    archive = bundle / "serbia.pmtiles"
    if not archive.is_file():
        raise ValueError(f"missing PMTiles archive: {archive}")
    with archive.open("rb") as stream:
        magic = stream.read(8)
    if magic[:7] != b"PMTiles" or len(magic) != 8 or magic[7] != 3:
        raise ValueError("archive is not PMTiles v3")

    version_output = str(run_tool(args.pmtiles, "version")).strip()
    version_prefix = f"pmtiles {args.pmtiles_version}, commit {args.pmtiles_commit},"
    if not version_output.startswith(version_prefix):
        raise ValueError(f"unexpected pmtiles CLI build: {version_output}")
    run_tool(args.pmtiles, "verify", str(archive))
    header = json.loads(str(run_tool(args.pmtiles, "show", str(archive), "--header-json")))
    metadata = json.loads(str(run_tool(args.pmtiles, "show", str(archive), "--metadata")))

    if header.get("tile_type") != "mvt" or header.get("tile_compression") != "gzip":
        raise ValueError("PMTiles must contain gzip-compressed MVT vector tiles")
    if header.get("minzoom") != args.min_zoom or header.get("maxzoom") != args.max_zoom:
        raise ValueError("PMTiles zoom range does not match the pinned build contract")
    expected_bounds = [float(part) for part in args.bounds.split(",")]
    actual_bounds = header.get("bounds")
    if not isinstance(actual_bounds, list) or len(actual_bounds) != 4:
        raise ValueError("PMTiles header bounds are missing")
    if any(abs(float(actual) - expected) > 1e-7 for actual, expected in zip(actual_bounds, expected_bounds)):
        raise ValueError(f"PMTiles bounds mismatch: expected {expected_bounds}, got {actual_bounds}")

    expected_metadata = {
        "name": "Aukcije Core Serbia Basemap",
        "description": "Offline Serbia-only vector basemap for Serbian judicial auction maps",
        "version": "2026.8.1",
        "type": "baselayer",
    }
    for key, expected in expected_metadata.items():
        if metadata.get(key) != expected:
            raise ValueError(f"metadata {key} mismatch: expected {expected!r}, got {metadata.get(key)!r}")
    attribution = metadata.get("attribution", "")
    if "OpenStreetMap contributors" not in attribution or "openstreetmap.org/copyright" not in attribution:
        raise ValueError("PMTiles metadata is missing OpenStreetMap attribution")
    vector_layers = metadata.get("vector_layers")
    if not isinstance(vector_layers, list):
        raise ValueError("PMTiles MVT metadata is missing vector_layers")
    layers = {item.get("id") for item in vector_layers if isinstance(item, dict)}
    if layers != EXPECTED_LAYERS:
        raise ValueError(f"PMTiles layer mismatch: expected {sorted(EXPECTED_LAYERS)}, got {sorted(layers)}")

    canonical_metadata = json.dumps(
        metadata, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    metadata_hash = sha256_bytes(canonical_metadata)
    if args.metadata_sha256 != "UNPINNED" and metadata_hash != args.metadata_sha256:
        raise ValueError(
            f"deterministic metadata hash mismatch: expected {args.metadata_sha256}, got {metadata_hash}"
        )

    smoke_reads = []
    for z, x, y in SMOKE_TILES:
        tile = run_tool(args.pmtiles, "tile", str(archive), str(z), str(x), str(y), binary=True)
        assert isinstance(tile, bytes)
        if not tile:
            raise ValueError(f"representative tile is missing or empty: {z}/{x}/{y}")
        smoke_reads.append(
            {"z": z, "x": x, "y": y, "sizeBytes": len(tile), "sha256": sha256_bytes(tile)}
        )

    style_report = validate_style(bundle, layers)
    report: dict[str, object] = {
        "pmtilesSpecVersion": 3,
        "pmtilesCli": version_output,
        "archiveStructuralVerification": "passed",
        "header": header,
        "metadataSha256": metadata_hash,
        "layers": sorted(layers),
        "smokeTiles": smoke_reads,
        "style": style_report,
    }
    if args.require_manifest:
        if not args.expected_command:
            raise ValueError("--expected-command is required with --require-manifest")
        validate_manifest(bundle, report, args.expected_command)
        report["manifestVerification"] = "passed"
    return report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--pmtiles", type=Path, required=True)
    parser.add_argument("--pmtiles-version", required=True)
    parser.add_argument("--pmtiles-commit", required=True)
    parser.add_argument("--bounds", required=True)
    parser.add_argument("--min-zoom", type=int, required=True)
    parser.add_argument("--max-zoom", type=int, required=True)
    parser.add_argument("--metadata-sha256", required=True)
    parser.add_argument("--expected-command")
    parser.add_argument("--require-manifest", action="store_true")
    args = parser.parse_args()
    try:
        report = validate(args)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        parser.error(str(error))
    print(json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
