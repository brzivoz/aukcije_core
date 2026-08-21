# Spike #32 - KO + parcel to map-location hit rate

Reproducible measurement code for [#32](https://github.com/brzivoz/aukcije_core/issues/32).
Not part of the Gradle build. No migrations, no tests, no CI, no production
wiring. Keep it only as long as the decision record must be reproducible.

The deliverable is the decision record at
`documentation/2026-08-21-spike-32-map-hit-rate.md`, not this code.

## Stages

| Script | Does | Writes |
|---|---|---|
| `01_fetch_auctions.py` | pull root-`7` listing + per-auction details | `out/corpus.json` |
| `02_extract_refs.py` | crude KO / parcel / street extraction | `out/refs.json`, `out/extraction_stats.json` |
| `03_load_gpkg.py` | download/extract + validate + index the official Address Registry GPKG | `out/registry.sqlite`, `out/gpkg_report.json` |
| `04_resolve.py` | tiered join, best tier per auction | `out/resolved.json`, `out/resolution_stats.json` |
| `05_make_geojson.py` | placements for a throwaway map | `out/placements.geojson` |
| `06_spotcheck.py` | sample 20 placements, validate committed verdict coordinates, optionally capture OSM reverse context | `out/spotcheck_sheet.md`, optionally `out/spotcheck_context.json` |

`out/` is git-ignored. Every number in the decision record comes from the
generated JSON reports/rows or the coordinate-bound `spotcheck-verdicts.json`.

## Running

Python 3.9+ is supported. Use an isolated environment so Fiona's native
dependencies do not affect the application build:

```bash
python3 -m venv .venv-spike32
.venv-spike32/bin/pip install -r requirements.txt
PYTHON_BIN=.venv-spike32/bin/python GPKG_SOURCE_DATE=2026-08-21 ./run_all.sh
```

The driver defaults to the official `kucni_broj_ar` export URL. The endpoint
currently returns a ZIP containing one GPKG; the loader handles either that
ZIP or a bare GPKG, records both SHA-256 values, verifies EPSG:25834, and
refuses a missing/ambiguous layer or required column mapping. To reuse a named
snapshot without downloading it again:

```bash
PYTHON_BIN=.venv-spike32/bin/python \
  GPKG=out/adresni_registar_download GPKG_SOURCE_DATE=2026-08-21 ./run_all.sh
```

Get the artifact URL from <https://data.gov.rs/sr/datasets/adresni-registar/>
and **record its date** — a hit rate without a named artifact date is not a
measurement.

Stages 5 and 6 end in manual work: inspect `out/placements.geojson` on
[geojson.io](https://geojson.io), then record every verdict in
`spotcheck-verdicts.json` against <https://a3.geosrbija.rs/> or another public
map. Stage 6 refuses to reuse a verdict when its coordinate has changed and
renders the committed checks into `out/spotcheck_sheet.md`. This optional
command captures independent OpenStreetMap reverse context
for the deterministic sample while respecting the public service's one-request
per-second limit; it does not assign verdicts automatically:

```bash
.venv-spike32/bin/python 06_spotcheck.py --n 20 --seed 32 --reverse-osm
```

## Offline / sandboxed hosts

On a host that cannot reach eAukcija or GeoSrbija, the committed fixture can
exercise the pipeline shape. It is not evidence for the decision record.

```bash
SOURCE=fixture python3 01_fetch_auctions.py --source fixture   # 86-record sample
```

`make_synthetic_gpkg.py` builds a fake registry so stages 3-5 can be
*executed*. It proves the code runs. It measures nothing, and its output must
never be quoted as a hit rate.

## Column names

`03_load_gpkg.py` recognizes the published 2026-08-21 schema but still scores
field names against candidate lists so a weekly schema change fails visibly.
Check the mapping on a new artifact and correct it with `--col role=field`:

```bash
python3 03_load_gpkg.py --gpkg ar.gpkg --inspect          # see the real schema
python3 03_load_gpkg.py --gpkg ar.gpkg --col parcel=broj_parcele --col ko_name=kat_opstina_ime
```

`broj_dela_parcele` is retained as an object-part attribute. It is deliberately
not appended as `/N`: doing so would fabricate a cadastral sub-parcel identity.
Resolution keys use municipality + official KO identity + parcel and reject
geographically dispersed one-to-many results instead of silently choosing the
first row.
