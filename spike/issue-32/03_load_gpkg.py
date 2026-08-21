#!/usr/bin/env python3
"""Stage 3 - download the official Address Registry GPKG and index it.

Produces registry.sqlite: a flat lookup keyed for the two joins stage 4
attempts, plus gpkg_report.json carrying the provenance and cost numbers
(#22 needs the load time and disk footprint).

    python3 03_load_gpkg.py                      # download, then load
    python3 03_load_gpkg.py --gpkg /path/ar.gpkg # load an existing file
    python3 03_load_gpkg.py --gpkg X --inspect   # print layers/schema only

Column names in the published GPKG are not blindly positional. The loader
recognizes the inspected 2026-08-21 fields, scores later names against the
candidate lists below, and prints what it chose; use --col role=field to
override a changed weekly schema.
"""
import argparse
import datetime
import hashlib
import json
import os
import shutil
import sqlite3
import sys
import time
import urllib.request
import zipfile

from common import norm_admin, norm_house_number, normkey, norm_parcel, out, write_json

DATASET_PAGE = "https://data.gov.rs/sr/datasets/adresni-registar/"
RESOURCE_ID = "be7c80e3-206b-46af-b31d-4b9f6ae596f9"
OFFICIAL_URL = (
    "https://download.geosrbija.rs/download-api/opendata-proxy/export"
    "?category=ar&layer=kucni_broj_ar&geometry=true"
    "&fileName=kucni_br_gpkg&format=gpkg"
)
DEFAULT_LAYER = None
EXPECTED_SOURCE_EPSG = 25834
TARGET_CRS = "EPSG:4326"

# role -> candidate field-name fragments, most specific first
COLUMN_CANDIDATES = {
    "ko_name": ["kat_opstina_ime", "naziv_katastarske_opstine",
                "katastarska_opstina_naziv", "ko_naziv", "naziv_ko",
                "katastarska_opstina", "ko_ime", "ko_name"],
    "ko_id": ["maticni_broj_katastarske_opstine", "ko_maticni",
              "maticni_broj_ko", "sifra_ko", "ko_sifra", "ko_id"],
    "parcel": ["broj_katastarske_parcele", "broj_parcele", "parcela_broj",
               "parcela", "brparcele"],
    # Only an explicit cadastral sub-parcel may be appended. The registry's
    # "broj dela parcele pod objektom" is a building-part ordinal, not /N.
    "parcel_sub": ["podbroj_katastarske_parcele", "podbroj_parcele", "podbroj"],
    "parcel_part": ["broj_dela_parcele", "broj_dela_katastarske_parcele",
                    "deo_parcele_pod_objektom"],
    "municipality": ["opstina_ime", "naziv_opstine", "opstina_naziv", "opstina"],
    "settlement": ["naziv_naseljenog_mesta", "naseljeno_mesto_naziv",
                   "naselje_naziv", "naziv_naselja", "naselje"],
    "street": ["naziv_ulice", "ulica_naziv", "ulica"],
    "house_number": ["kucni_broj", "kbr", "broj"],
    "status": ["vrsta_stanja", "status", "stanje"],
    "retired": ["retired", "datum_prestanka", "datum_brisanja"],
}

DDL = """
CREATE TABLE IF NOT EXISTS address_point (
  fid            TEXT,
  ko_name        TEXT,
  ko_key         TEXT,
  ko_id          TEXT,
  parcel_raw     TEXT,
  parcel_norm    TEXT,
  parcel_part    TEXT,
  municipality   TEXT,
  municipality_key TEXT,
  settlement     TEXT,
  settlement_key TEXT,
  street         TEXT,
  street_key     TEXT,
  house_number   TEXT,
  house_number_key TEXT,
  status         TEXT,
  retired        TEXT,
  is_active      INTEGER,
  lon            REAL,
  lat            REAL
);
"""
INDEXES = [
    "CREATE INDEX IF NOT EXISTS ix_ko_parcel ON address_point(municipality_key, ko_key, parcel_norm, is_active)",
    "CREATE INDEX IF NOT EXISTS ix_global_ko_parcel ON address_point(ko_key, parcel_norm, is_active)",
    "CREATE INDEX IF NOT EXISTS ix_addr ON address_point(municipality_key, settlement_key, street_key, house_number_key, is_active)",
    "CREATE INDEX IF NOT EXISTS ix_street ON address_point(municipality_key, settlement_key, street_key, is_active)",
    "CREATE INDEX IF NOT EXISTS ix_global_addr ON address_point(settlement_key, street_key, house_number_key, is_active)",
    "CREATE INDEX IF NOT EXISTS ix_global_street ON address_point(settlement_key, street_key, is_active)",
    "CREATE INDEX IF NOT EXISTS ix_ko ON address_point(municipality_key, ko_key, is_active)",
    "CREATE INDEX IF NOT EXISTS ix_global_ko ON address_point(ko_key, is_active)",
    "CREATE INDEX IF NOT EXISTS ix_settlement ON address_point(municipality_key, settlement_key, is_active)",
    "CREATE INDEX IF NOT EXISTS ix_municipality ON address_point(municipality_key, is_active)",
]


def resolve_columns(field_names, overrides):
    """Map each role to a real field name by scoring name fragments."""
    lowered = {f.lower(): f for f in field_names}
    chosen = {}
    for role, fragments in COLUMN_CANDIDATES.items():
        if role in overrides:
            chosen[role] = overrides[role]
            continue
        best = None
        for rank, frag in enumerate(fragments):
            exact = [orig for low, orig in lowered.items() if low == frag]
            if exact:
                best = (rank, 0, exact[0])
                break
            partial = sorted(orig for low, orig in lowered.items() if frag in low)
            if partial and best is None:
                best = (rank, 1, partial[0])
        chosen[role] = best[2] if best else None
    return chosen


def download(url, dest):
    t0 = time.time()
    sha = hashlib.sha256()
    total = 0
    with urllib.request.urlopen(url, timeout=300) as resp, open(dest, "wb") as fh:
        while True:
            chunk = resp.read(1 << 20)
            if not chunk:
                break
            sha.update(chunk)
            fh.write(chunk)
            total += len(chunk)
            if total % (64 << 20) < (1 << 20):
                print(f"  ...{total / 1e6:.0f} MB", file=sys.stderr)
    return {
        "url": url,
        "bytes": total,
        "sha256": sha.hexdigest(),
        "download_seconds": round(time.time() - t0, 1),
        "downloaded_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }


def sha256_file(path):
    sha = hashlib.sha256()
    with open(path, "rb") as fh:
        while True:
            chunk = fh.read(1 << 20)
            if not chunk:
                break
            sha.update(chunk)
    return sha.hexdigest()


def materialize_gpkg(path):
    """Return a bare GPKG path, extracting the official ZIP when necessary."""
    if not zipfile.is_zipfile(path):
        return path, None
    with zipfile.ZipFile(path) as archive:
        members = [m for m in archive.infolist()
                   if not m.is_dir() and m.filename.lower().endswith(".gpkg")]
        if len(members) != 1:
            raise RuntimeError(
                f"expected one .gpkg in {path}, found {[m.filename for m in members]}"
            )
        member = members[0]
        dest = out("adresni_registar.gpkg")
        t0 = time.time()
        with archive.open(member) as src, open(dest, "wb") as dst:
            shutil.copyfileobj(src, dst, length=1 << 20)
        return dest, {
            "archive_member": member.filename,
            "uncompressed_bytes": member.file_size,
            "extract_seconds": round(time.time() - t0, 1),
        }


def is_active_status(value):
    """Treat blank/current statuses as active and known retirement markers as inactive."""
    key = normkey(value)
    if not key:
        return True
    inactive_markers = (
        "neakt", "obris", "brisan", "istor", "prestao", "van snage",
        "retired", "inactive", "deleted",
    )
    return not any(marker in key for marker in inactive_markers)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", help=f"direct GPKG/ZIP URL; find it on {DATASET_PAGE}")
    ap.add_argument("--gpkg", help="use an already-downloaded GPKG or ZIP")
    ap.add_argument("--source-date", default=datetime.date.today().isoformat(),
                    help="date assigned to this weekly source snapshot (YYYY-MM-DD)")
    ap.add_argument("--layer", default=DEFAULT_LAYER)
    ap.add_argument("--inspect", action="store_true",
                    help="print layers and schema, then exit")
    ap.add_argument("--limit", type=int, default=0, help="stop after N features")
    ap.add_argument("--col", action="append", default=[], metavar="role=field",
                    help="override an autodetected column")
    args = ap.parse_args()

    import fiona
    from pyproj import Transformer

    report = {
        "dataset_page": DATASET_PAGE,
        "resource_id": RESOURCE_ID,
        "source_date": args.source_date,
        "requested_layer": args.layer,
        "expected_source_epsg": EXPECTED_SOURCE_EPSG,
        "target_crs": TARGET_CRS,
    }

    gpkg = args.gpkg
    if not gpkg:
        if not args.url:
            print("Need --gpkg or --url. The weekly artifact is linked from\n"
                  f"  {DATASET_PAGE}\n"
                  "Record the artifact date; every number downstream is only "
                  "meaningful against a named artifact.", file=sys.stderr)
            return 2
        gpkg = out("adresni_registar_download")
        print(f"downloading {args.url}", file=sys.stderr)
        report["download"] = download(args.url, gpkg)

    source_path = gpkg
    source_sha256 = sha256_file(source_path)
    gpkg, extraction = materialize_gpkg(source_path)
    report["source_artifact"] = {
        "path": source_path,
        "bytes": os.path.getsize(source_path),
        "sha256": source_sha256,
        "is_zip": bool(extraction),
    }
    if extraction:
        extraction["gpkg_sha256"] = sha256_file(gpkg)
        report["extraction"] = extraction
    report["gpkg_path"] = gpkg
    report["gpkg_bytes"] = os.path.getsize(gpkg)

    layers = fiona.listlayers(gpkg)
    report["layers"] = layers
    print(f"layers: {layers}", file=sys.stderr)

    layer = args.layer
    if layer is None:
        house_layers = [name for name in layers if "kuc" in normkey(name)]
        if len(house_layers) == 1:
            layer = house_layers[0]
        elif len(layers) == 1:
            layer = layers[0]
    if layer not in layers:
        print(f"layer {layer!r} not in {layers}; pass --layer", file=sys.stderr)
        return 2
    report["layer"] = layer

    overrides = dict(kv.split("=", 1) for kv in args.col)

    t0 = time.time()
    with fiona.open(gpkg, layer=layer) as src:
        schema = dict(src.schema["properties"])
        report["feature_count"] = len(src)
        from pyproj import CRS
        source_crs = CRS.from_user_input(src.crs_wkt or src.crs)
        report["source_crs"] = source_crs.to_string()
        report["source_epsg"] = source_crs.to_epsg()
        report["crs_wkt"] = source_crs.to_wkt()
        report["geometry_type"] = src.schema.get("geometry")
        report["schema"] = schema
        report["schema_sha256"] = hashlib.sha256(
            json.dumps(schema, ensure_ascii=False, sort_keys=True).encode("utf-8")
        ).hexdigest()
        cols = resolve_columns(schema.keys(), overrides)
        report["resolved_columns"] = cols

        invalid_overrides = {
            role: field for role, field in overrides.items() if field not in schema
        }
        if invalid_overrides:
            print(f"FATAL: override fields not in schema: {invalid_overrides}",
                  file=sys.stderr)
            return 2

        print("\nresolved columns:", file=sys.stderr)
        for role, field in cols.items():
            print(f"  {role:14s} -> {field}", file=sys.stderr)
        if args.inspect:
            write_json(out("gpkg_report.json"), report)
            print(json.dumps(schema, indent=2, ensure_ascii=False))
            return 0

        if report["source_epsg"] != EXPECTED_SOURCE_EPSG:
            print(
                f"\nFATAL: expected EPSG:{EXPECTED_SOURCE_EPSG}, got "
                f"{report['source_epsg']}",
                file=sys.stderr,
            )
            return 2

        missing = [r for r in (
            "ko_name", "ko_id", "parcel", "municipality", "settlement",
            "street", "house_number",
        ) if not cols.get(r)]
        if missing:
            print(f"\nFATAL: no column found for {missing}. Inspect with "
                  f"--inspect and pass --col role=field.", file=sys.stderr)
            return 2

        db_path = out("registry.sqlite")
        if os.path.exists(db_path):
            os.remove(db_path)
        db = sqlite3.connect(db_path)
        db.executescript(DDL)

        tf = Transformer.from_crs(source_crs, TARGET_CRS, always_xy=True)
        batch, n, skipped = [], 0, 0

        for feat in src:
            props = feat["properties"]
            geom = feat.get("geometry")
            if not geom or geom["type"] != "Point":
                skipped += 1
                continue
            x, y = geom["coordinates"][0], geom["coordinates"][1]
            lon, lat = tf.transform(x, y)

            def g(role):
                f = cols.get(role)
                v = props.get(f) if f else None
                return None if v is None else str(v).strip()

            parcel_raw = g("parcel")
            sub = g("parcel_sub")
            if parcel_raw and sub and sub not in ("0", ""):
                parcel_raw = f"{parcel_raw}/{sub}"

            ko_name = g("ko_name")
            municipality = g("municipality")
            settlement = g("settlement")
            street = g("street")
            house_number = g("house_number")
            status = g("status")
            retired = g("retired")
            batch.append((
                feat.get("id"), ko_name, normkey(ko_name), g("ko_id"),
                parcel_raw, norm_parcel(parcel_raw), g("parcel_part"),
                municipality, norm_admin(municipality),
                settlement, normkey(settlement),
                street, normkey(street),
                house_number, norm_house_number(house_number),
                status, retired, int(not retired and is_active_status(status)),
                lon, lat,
            ))
            n += 1
            if len(batch) >= 20000:
                db.executemany("INSERT INTO address_point VALUES "
                               "(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", batch)
                db.commit()
                batch.clear()
                print(f"  {n} rows", file=sys.stderr)
            if args.limit and n >= args.limit:
                break

        if batch:
            db.executemany("INSERT INTO address_point VALUES "
                           "(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", batch)
        db.commit()

    for stmt in INDEXES:
        db.execute(stmt)
    db.commit()

    report["rows_loaded"] = n
    report["rows_skipped_no_point"] = skipped
    report["load_seconds"] = round(time.time() - t0, 1)
    report["sqlite_bytes"] = os.path.getsize(db_path)

    # parcel identity semantics: how many address points per (KO, parcel)?
    cur = db.execute("""
        SELECT rows_per, COUNT(*) FROM (
          SELECT COUNT(*) AS rows_per FROM address_point
          WHERE parcel_norm <> '' AND ko_key <> '' AND is_active = 1
          GROUP BY COALESCE(NULLIF(ko_id, ''), ko_key), parcel_norm
        ) GROUP BY rows_per ORDER BY rows_per LIMIT 25
    """)
    report["points_per_parcel_histogram"] = {str(k): v for k, v in cur.fetchall()}
    report["distinct_ko_parcel"] = db.execute(
        "SELECT COUNT(*) FROM (SELECT 1 FROM address_point "
        "WHERE parcel_norm <> '' AND ko_key <> '' AND is_active = 1 "
        "GROUP BY COALESCE(NULLIF(ko_id, ''), ko_key), parcel_norm)"
    ).fetchone()[0]
    one_point_parcels = report["points_per_parcel_histogram"].get("1", 0)
    many_point_parcels = report["distinct_ko_parcel"] - one_point_parcels
    max_points_per_parcel = db.execute("""
        SELECT MAX(rows_per) FROM (
          SELECT COUNT(*) AS rows_per FROM address_point
          WHERE parcel_norm <> '' AND ko_key <> '' AND is_active = 1
          GROUP BY COALESCE(NULLIF(ko_id, ''), ko_key), parcel_norm
        )
    """).fetchone()[0]
    report["registry_parcel_identity"] = {
        "one_point_parcels": one_point_parcels,
        "one_point_pct": round(
            100.0 * one_point_parcels / report["distinct_ko_parcel"], 2
        ),
        "many_point_parcels": many_point_parcels,
        "many_point_pct": round(
            100.0 * many_point_parcels / report["distinct_ko_parcel"], 2
        ),
        "max_points_per_parcel": max_points_per_parcel,
        "zero_point_note": (
            "A registry identity cannot have zero rows; zero-hit auction "
            "identities are measured by 04_resolve.py."
        ),
    }
    report["rows_with_parcel"] = db.execute(
        "SELECT COUNT(*) FROM address_point WHERE parcel_norm <> '' "
        "AND is_active = 1").fetchone()[0]
    report["active_rows"] = db.execute(
        "SELECT COUNT(*) FROM address_point WHERE is_active = 1").fetchone()[0]
    report["inactive_rows"] = db.execute(
        "SELECT COUNT(*) FROM address_point WHERE is_active = 0").fetchone()[0]
    report["retired_rows"] = db.execute(
        "SELECT COUNT(*) FROM address_point WHERE retired IS NOT NULL "
        "AND retired <> ''").fetchone()[0]
    report["status_counts"] = {
        str(status): count for status, count in db.execute(
            "SELECT status, COUNT(*) FROM address_point GROUP BY status "
            "ORDER BY COUNT(*) DESC"
        ).fetchall()
    }
    report["rows_with_parcel_part"] = db.execute(
        "SELECT COUNT(*) FROM address_point WHERE parcel_part IS NOT NULL "
        "AND parcel_part <> ''"
    ).fetchone()[0]
    db.close()

    write_json(out("gpkg_report.json"), report)
    print(json.dumps({k: v for k, v in report.items() if k != "schema"},
                     indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
