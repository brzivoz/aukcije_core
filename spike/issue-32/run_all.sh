#!/usr/bin/env bash
# Spike #32 driver. Throwaway.
#
#   ./run_all.sh                              live pull + official weekly export
#   GPKG=/path/ar.gpkg ./run_all.sh           reuse an already-downloaded GPKG
#   SOURCE=fixture GPKG=... ./run_all.sh      offline corpus
set -euo pipefail
cd "$(dirname "$0")"

SOURCE="${SOURCE:-live}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
GPKG_URL="${GPKG_URL:-https://download.geosrbija.rs/download-api/opendata-proxy/export?category=ar&layer=kucni_broj_ar&geometry=true&fileName=kucni_br_gpkg&format=gpkg}"
GPKG_SOURCE_DATE="${GPKG_SOURCE_DATE:-$(date -u +%F)}"
GPKG="${GPKG:-}"

"$PYTHON_BIN" -c 'import sys; assert sys.version_info >= (3, 9), "Python 3.9+ is required"'
"$PYTHON_BIN" 01_fetch_auctions.py --source "$SOURCE"
"$PYTHON_BIN" 02_extract_refs.py

if [[ -n "$GPKG" ]]; then
  "$PYTHON_BIN" 03_load_gpkg.py --gpkg "$GPKG" --source-date "$GPKG_SOURCE_DATE"
elif [[ -n "$GPKG_URL" ]]; then
  "$PYTHON_BIN" 03_load_gpkg.py --url "$GPKG_URL" --source-date "$GPKG_SOURCE_DATE"
else
  echo "Set GPKG=<file> or GPKG_URL=<url>. Find the weekly artifact at" >&2
  echo "  https://data.gov.rs/sr/datasets/adresni-registar/" >&2
  exit 2
fi

"$PYTHON_BIN" 04_resolve.py
"$PYTHON_BIN" 05_make_geojson.py
"$PYTHON_BIN" 06_spotcheck.py --n 20

echo
echo "Now inspect out/placements.geojson and out/spotcheck_sheet.md."
echo "Record changed coordinates in spotcheck-verdicts.json, rerun stage 6,"
echo "then fold the generated evidence into"
echo "documentation/2026-08-21-spike-32-map-hit-rate.md"
echo "Optional public-map context:"
echo "  $PYTHON_BIN 06_spotcheck.py --n 20 --seed 32 --reverse-osm"
