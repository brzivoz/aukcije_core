from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
BASEMAP = REPO_ROOT / "basemap"
VERIFY_SOURCE = BASEMAP / "scripts" / "verify_source.py"
VALIDATE_BUNDLE = BASEMAP / "scripts" / "validate_bundle.py"
LAYERS = ["boundaries", "buildings", "landuse", "places", "pois", "roads", "water"]
BOUNDS = [18.8, 42.2, 23.1, 46.3]
PMTILES_VERSION = "1.31.2"
PMTILES_COMMIT = "a3e4951ea6a0477b784c27c1dcbfd9c130878c5a"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class SourceVerificationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.name = "serbia-test.osm.pbf"
        self.pbf = self.root / self.name
        self.checksum = self.root / f"{self.name}.md5"
        self.data = b"small deterministic OSM PBF stand-in\n"
        self.pbf.write_bytes(self.data)
        self.md5 = hashlib.md5(self.data).hexdigest()
        self.checksum.write_text(f"{self.md5}  {self.name}\n", encoding="utf-8")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def command(self) -> list[str]:
        return [
            "python3",
            str(VERIFY_SOURCE),
            "--pbf",
            str(self.pbf),
            "--checksum",
            str(self.checksum),
            "--expected-name",
            self.name,
            "--expected-md5",
            self.md5,
            "--expected-sha256",
            sha256(self.data),
            "--expected-size",
            str(len(self.data)),
        ]

    def test_exact_source_and_downloaded_checksum_pass(self) -> None:
        completed = subprocess.run(self.command(), check=True, text=True, capture_output=True)
        report = json.loads(completed.stdout)
        self.assertEqual(self.md5, report["md5"])
        self.assertEqual(sha256(self.data), report["sha256"])

    def test_corrupt_source_fails_closed(self) -> None:
        self.pbf.write_bytes(self.data + b"corruption")
        completed = subprocess.run(self.command(), check=False, text=True, capture_output=True)
        self.assertNotEqual(0, completed.returncode)
        self.assertIn("source size mismatch", completed.stderr)

    def test_mismatched_downloaded_checksum_fails_closed(self) -> None:
        self.checksum.write_text(f"{'0' * 32}  {self.name}\n", encoding="utf-8")
        completed = subprocess.run(self.command(), check=False, text=True, capture_output=True)
        self.assertNotEqual(0, completed.returncode)
        self.assertIn("differs from the reviewed MD5 pin", completed.stderr)


class BundleValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.bundle = self.root / "bundle"
        self.bundle.mkdir()
        (self.bundle / "serbia.pmtiles").write_bytes(b"PMTiles\x03fixture")
        shutil.copyfile(BASEMAP / "style.json", self.bundle / "style.json")
        shutil.copyfile(BASEMAP / "THIRD_PARTY_NOTICES.md", self.bundle / "THIRD_PARTY_NOTICES.md")
        for range_name in ("0-255", "256-511", "1024-1279", "8192-8447"):
            path = self.bundle / "glyphs" / "Noto Sans Regular" / f"{range_name}.pbf"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(f"glyph-{range_name}".encode())
        sprite_dir = self.bundle / "sprites"
        sprite_dir.mkdir()
        for scale in ("", "@2x"):
            (sprite_dir / f"light{scale}.json").write_text(
                json.dumps({"townspot": {"x": 0, "y": 0, "width": 1, "height": 1}}),
                encoding="utf-8",
            )
            (sprite_dir / f"light{scale}.png").write_bytes(b"\x89PNG\r\n\x1a\nfixture")
        license_dir = self.bundle / "licenses"
        license_dir.mkdir()
        (license_dir / "Noto-OFL-1.1.txt").write_text("OFL fixture", encoding="utf-8")
        (license_dir / "Tangram-Icons-MIT.md").write_text("MIT fixture", encoding="utf-8")

        self.metadata = {
            "name": "Aukcije Core Serbia Basemap",
            "description": "Offline Serbia-only vector basemap for Serbian judicial auction maps",
            "version": "2026.8.1",
            "type": "baselayer",
            "attribution": (
                '<a href="https://www.openstreetmap.org/copyright">'
                "&copy; OpenStreetMap contributors</a>"
            ),
            "vector_layers": [{"id": layer, "fields": {}} for layer in LAYERS],
        }
        self.header = {
            "tile_compression": "gzip",
            "tile_type": "mvt",
            "minzoom": 3,
            "maxzoom": 14,
            "bounds": BOUNDS,
            "center": [20.45, 44.78, 7],
        }
        self.tool_dir = self.root / "tool"
        self.tool_dir.mkdir()
        self.write_tool_data()
        self.pmtiles = self.tool_dir / "pmtiles"
        self.pmtiles.write_text(
            """#!/usr/bin/env python3
import json
import pathlib
import sys
root = pathlib.Path(__file__).parent
args = sys.argv[1:]
if args[0] == 'version':
    print('pmtiles 1.31.2, commit a3e4951ea6a0477b784c27c1dcbfd9c130878c5a, built at fixture')
elif args[0] == 'verify':
    raise SystemExit(0)
elif args[0] == 'show' and '--header-json' in args:
    print((root / 'header.json').read_text())
elif args[0] == 'show' and '--metadata' in args:
    print((root / 'metadata.json').read_text())
elif args[0] == 'tile':
    sys.stdout.buffer.write(('tile-' + '-'.join(args[-3:])).encode())
else:
    print('unsupported fake invocation', file=sys.stderr)
    raise SystemExit(2)
""",
            encoding="utf-8",
        )
        self.pmtiles.chmod(0o755)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write_tool_data(self) -> None:
        (self.tool_dir / "metadata.json").write_text(json.dumps(self.metadata), encoding="utf-8")
        (self.tool_dir / "header.json").write_text(json.dumps(self.header), encoding="utf-8")

    def metadata_hash(self) -> str:
        canonical = json.dumps(
            self.metadata, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
        return sha256(canonical)

    def command(self) -> list[str]:
        return [
            "python3",
            str(VALIDATE_BUNDLE),
            "--bundle",
            str(self.bundle),
            "--pmtiles",
            str(self.pmtiles),
            "--pmtiles-version",
            PMTILES_VERSION,
            "--pmtiles-commit",
            PMTILES_COMMIT,
            "--bounds",
            ",".join(str(value) for value in BOUNDS),
            "--min-zoom",
            "3",
            "--max-zoom",
            "14",
            "--metadata-sha256",
            self.metadata_hash(),
        ]

    def test_valid_local_bundle_and_three_smoke_reads_pass(self) -> None:
        completed = subprocess.run(self.command(), check=True, text=True, capture_output=True)
        report = json.loads(completed.stdout)
        self.assertEqual(3, report["pmtilesSpecVersion"])
        self.assertEqual(LAYERS, report["layers"])
        self.assertEqual(3, len(report["smokeTiles"]))
        self.assertEqual([], report["style"]["externalRuntimeAssets"])

    def test_wrong_pmtiles_version_fails(self) -> None:
        (self.bundle / "serbia.pmtiles").write_bytes(b"PMTiles\x02fixture")
        completed = subprocess.run(self.command(), check=False, text=True, capture_output=True)
        self.assertNotEqual(0, completed.returncode)
        self.assertIn("not PMTiles v3", completed.stderr)

    def test_missing_expected_layer_fails(self) -> None:
        self.metadata["vector_layers"] = self.metadata["vector_layers"][:-1]
        self.write_tool_data()
        completed = subprocess.run(self.command(), check=False, text=True, capture_output=True)
        self.assertNotEqual(0, completed.returncode)
        self.assertIn("PMTiles layer mismatch", completed.stderr)

    def test_external_runtime_asset_fails(self) -> None:
        style_path = self.bundle / "style.json"
        style = json.loads(style_path.read_text("utf-8"))
        style["glyphs"] = "https://protomaps.github.io/fonts/{fontstack}/{range}.pbf"
        style_path.write_text(json.dumps(style), encoding="utf-8")
        completed = subprocess.run(self.command(), check=False, text=True, capture_output=True)
        self.assertNotEqual(0, completed.returncode)
        self.assertIn("style glyph URL", completed.stderr)


class PinContractTest(unittest.TestCase):
    def test_sources_tools_and_assets_are_immutable(self) -> None:
        versions = (BASEMAP / "versions.env").read_text("utf-8")
        self.assertIn("SOURCE_FILENAME=serbia-260801.osm.pbf", versions)
        self.assertRegex(versions, r"SOURCE_SHA256=[0-9a-f]{64}")
        self.assertRegex(versions, r"PLANETILER_IMAGE=.+@sha256:[0-9a-f]{64}")
        self.assertRegex(versions, r"BASEMAP_METADATA_SHA256=[0-9a-f]{64}")
        self.assertNotIn("UNPINNED", versions)
        self.assertNotIn("/latest/", versions)

        for raw_line in (BASEMAP / "assets.lock").read_text("utf-8").splitlines():
            if not raw_line or raw_line.startswith("#"):
                continue
            digest, size, relative, url = raw_line.split("\t")
            self.assertRegex(digest, r"^[0-9a-f]{64}$")
            self.assertGreater(int(size), 0)
            self.assertFalse(Path(relative).is_absolute())
            self.assertRegex(url, r"githubusercontent\.com/.+/[0-9a-f]{40}/")


if __name__ == "__main__":
    unittest.main()
