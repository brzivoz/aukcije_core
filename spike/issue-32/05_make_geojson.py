#!/usr/bin/env python3
"""Stage 5 - dump resolved placements as GeoJSON for a throwaway map.

Drag out/placements.geojson onto https://geojson.io (or open it in QGIS).
Features are coloured by tier so a wrong-looking cluster is obvious at a
glance. The offline basemap in #24/#25 is not needed for this.

    python3 05_make_geojson.py
"""
import sys

from common import out, read_json, write_json

TIER_COLOUR = {
    "PARCEL_JOIN": "#1b7837",
    "ADDRESS_POINT": "#5aae61",
    "STREET": "#f1a340",
    "KO_CENTROID": "#d73027",
    "SETTLEMENT_CENTROID": "#b2182b",
    "MUNICIPALITY_CENTROID": "#67001f",
}


def main():
    resolved = read_json(out("resolved.json"))
    features = []
    for r in resolved:
        if r["lon"] is None:
            continue
        features.append({
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [r["lon"], r["lat"]]},
            "properties": {
                "id": r["id"],
                "url": r["url"],
                "tier": r["tier"],
                "precision": r["precision"],
                "ko": r["ko_field"],
                "parcel": r["parcel"],
                "municipality": r["municipality"],
                "settlement": r["settlement"],
                "candidates": r["candidates"],
                "spread_m": r["spread_m"],
                "marker-color": TIER_COLOUR.get(r["tier"], "#777777"),
                "title": f"{r['tier']} - {r['ko_field']} {r['parcel'] or ''}".strip(),
            },
        })

    path = write_json(out("placements.geojson"),
                      {"type": "FeatureCollection", "features": features})
    skipped = len(resolved) - len(features)
    print(f"{len(features)} features -> {path}")
    if skipped:
        print(f"{skipped} auction(s) had no placement at any tier")
    print("Open at https://geojson.io or in QGIS.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
