#!/usr/bin/env python3
"""CODE-PATH CHECK ONLY - builds a fake Address Registry GPKG.

This exists so stages 3-5 can be executed and shown to work in an environment
that cannot reach data.gov.rs. It is NOT a data source. Any hit rate produced
against this file is a property of this generator, not of Serbia's address
registry, and must never appear in the decision record as a measurement.

    python3 make_synthetic_gpkg.py out/synthetic.gpkg
"""
import json
import random
import sys

import fiona
from fiona.crs import CRS
from pyproj import Transformer

from common import out, read_json

SCHEMA = {
    "geometry": "Point",
    "properties": {
        "ko_naziv": "str", "ko_maticni_broj": "str",
        "broj_parcele": "str", "podbroj_parcele": "str",
        "opstina_naziv": "str", "naselje_naziv": "str",
        "ulica_naziv": "str", "kucni_broj": "str", "vrsta_stanja": "str",
    },
}


def main():
    dest = sys.argv[1] if len(sys.argv) > 1 else out("synthetic.gpkg")
    refs = read_json(out("refs.json"))
    rng = random.Random(32)
    tf = Transformer.from_crs("EPSG:4326", "EPSG:25834", always_xy=True)

    # Cover ~70% of the extracted (KO, parcel) pairs so the join has both
    # hits and misses to exercise; the rest get KO-only coverage.
    pairs = [(r["ko_field"], r["parcel"], r) for r in refs if r["parcel"]]
    rng.shuffle(pairs)
    covered = pairs[: int(len(pairs) * 0.7)]

    records = []
    for ko, parcel, r in covered:
        main_no, _, sub = parcel.partition("/")
        # some parcels carry several house numbers - exercise one-to-many
        for k in range(rng.choice([1, 1, 1, 2, 3])):
            lon = 20.0 + rng.random() * 2.0
            lat = 43.5 + rng.random() * 2.0
            x, y = tf.transform(lon, lat)
            records.append({
                "geometry": {"type": "Point", "coordinates": (x, y)},
                "properties": {
                    "ko_naziv": ko,
                    "ko_maticni_broj": str(700000 + rng.randint(0, 9999)),
                    "broj_parcele": main_no,
                    "podbroj_parcele": sub or "0",
                    "opstina_naziv": r["municipality"],
                    "naselje_naziv": r["settlement"],
                    "ulica_naziv": r["street"] or "Главна",
                    "kucni_broj": r["house_number"] or str(rng.randint(1, 90)),
                    "vrsta_stanja": "aktivan",
                },
            })
    # KO-only filler so centroid fallback has something to average
    for r in refs:
        if not r["ko_field"]:
            continue
        lon = 20.0 + rng.random() * 2.0
        lat = 43.5 + rng.random() * 2.0
        x, y = tf.transform(lon, lat)
        records.append({
            "geometry": {"type": "Point", "coordinates": (x, y)},
            "properties": {
                "ko_naziv": r["ko_field"], "ko_maticni_broj": "700000",
                "broj_parcele": str(rng.randint(9000, 9999)),
                "podbroj_parcele": "0",
                "opstina_naziv": r["municipality"],
                "naselje_naziv": r["settlement"],
                "ulica_naziv": r["street"] or "Споредна",
                "kucni_broj": str(rng.randint(1, 90)),
                "vrsta_stanja": "aktivan",
            },
        })

    with fiona.open(dest, "w", driver="GPKG", layer="kucni_broj",
                    crs=CRS.from_epsg(25834), schema=SCHEMA) as dst:
        dst.writerecords(records)
    print(f"wrote {len(records)} synthetic features -> {dest}")
    print("REMINDER: synthetic. Not a measurement.")


if __name__ == "__main__":
    main()
