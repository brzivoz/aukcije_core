#!/usr/bin/env python3
"""Stage 4 - attempt each resolution tier in order and record which one won.

Tier order mirrors the order proposed in #23 so the measurement maps onto the
issue it is meant to inform:

  PARCEL_JOIN          (ko_key, parcel_norm) hit in the registry
  ADDRESS_POINT        settlement + street + house number
  STREET               settlement + street, unambiguous
  KO_CENTROID          mean of the KO's registry points
  SETTLEMENT_CENTROID  mean of the settlement's registry points
  MUNICIPALITY_CENTROID
  NONE

PARCEL_JOIN yields a house-number point that sits on the parcel. Per #23 that
is ADDRESS precision, not PARCEL precision - only verified cadastral geometry
from #21 earns PARCEL. The `precision` column below says so explicitly.

    python3 04_resolve.py
"""
import json
import sqlite3
import statistics
import sys
from collections import Counter

from common import (norm_admin, norm_house_number, normkey, out, read_json,
                    write_json)

TIERS = ["PARCEL_JOIN", "ADDRESS_POINT", "STREET", "KO_CENTROID",
         "SETTLEMENT_CENTROID", "MUNICIPALITY_CENTROID", "NONE"]

PRECISION_OF = {
    "PARCEL_JOIN": "ADDRESS",          # registry house-number point on the parcel
    "ADDRESS_POINT": "ADDRESS",
    "STREET": "STREET",
    "KO_CENTROID": "KO",
    "SETTLEMENT_CENTROID": "SETTLEMENT",
    "MUNICIPALITY_CENTROID": "MUNICIPALITY",
    "NONE": "NONE",
}

# A one-to-many parcel join is usable only when its address points form a local
# cluster. Exact-address duplicates must be substantially tighter.
MAX_PARCEL_SPREAD_M = 2000.0
MAX_ADDRESS_SPREAD_M = 250.0


def mean_point(rows):
    return (statistics.fmean(r[0] for r in rows),
            statistics.fmean(r[1] for r in rows))


def medoid_point(rows):
    """Return an observed point nearest the centre, never a fabricated mean."""
    centre_lon, centre_lat = mean_point(rows)
    row = min(
        rows,
        key=lambda r: (r[0] - centre_lon) ** 2 + (r[1] - centre_lat) ** 2,
    )
    return row[0], row[1]


def spread_metres(rows):
    """Crude bounding-box diagonal in metres, to expose fake precision."""
    if len(rows) < 2:
        return 0.0
    lons = [r[0] for r in rows]
    lats = [r[1] for r in rows]
    dlon = (max(lons) - min(lons)) * 111320 * 0.72   # cos(44 deg)
    dlat = (max(lats) - min(lats)) * 111320
    return round((dlon ** 2 + dlat ** 2) ** 0.5, 1)


def main():
    refs = read_json(out("refs.json"))
    db = sqlite3.connect(out("registry.sqlite"))
    q = db.execute

    results = []
    tier_counts = Counter()
    ambiguity = Counter()

    for r in refs:
        ko_key = r["ko_key"]
        parcels = r.get("parcels") or ([r["parcel"]] if r.get("parcel") else [])
        municipality_key = norm_admin(r["municipality"])
        st_key = None
        rows, tier, note = [], "NONE", None
        matched_parcel = None
        identity_mode = None

        if ko_key and municipality_key:
            for parcel in parcels:
                candidate_rows = q(
                    "SELECT lon, lat, ko_id FROM address_point "
                    "WHERE municipality_key = ? AND ko_key = ? "
                    "AND parcel_norm = ? AND is_active = 1",
                    (municipality_key, ko_key, parcel),
                ).fetchall()
                candidate_identity_mode = "MUNICIPALITY_KO_NAME"
                if not candidate_rows:
                    candidate_rows = q(
                        "SELECT lon, lat, ko_id FROM address_point "
                        "WHERE ko_key = ? AND parcel_norm = ? AND is_active = 1",
                        (ko_key, parcel),
                    ).fetchall()
                    candidate_identity_mode = "UNIQUE_KO_ID_FALLBACK"
                if not candidate_rows:
                    continue
                ko_ids = {row[2] for row in candidate_rows if row[2]}
                spread = spread_metres(candidate_rows)
                if len(ko_ids) > 1 or spread > MAX_PARCEL_SPREAD_M:
                    ambiguity["PARCEL_JOIN_REJECTED"] += 1
                    continue
                rows = candidate_rows
                tier = "PARCEL_JOIN"
                matched_parcel = parcel
                identity_mode = candidate_identity_mode
                note = f"{len(rows)} registry point(s) on parcel"
                candidate_bucket = str(len(rows)) if len(rows) < 10 else "10_PLUS"
                ambiguity[f"PARCEL_JOIN_{candidate_bucket}"] += 1
                break

        if (tier == "NONE" and municipality_key and r["settlement"]
                and r["street"] and r["house_number"]):
            st_key = normkey(r["street"])
            candidate_rows = q(
                "SELECT lon, lat, municipality_key FROM address_point "
                "WHERE municipality_key = ? "
                "AND settlement_key = ? AND street_key = ? "
                "AND house_number_key = ? AND is_active = 1",
                (municipality_key, normkey(r["settlement"]), st_key,
                 norm_house_number(r["house_number"])),
            ).fetchall()
            address_identity_mode = "MUNICIPALITY_ADDRESS"
            if not candidate_rows:
                candidate_rows = q(
                    "SELECT lon, lat, municipality_key FROM address_point "
                    "WHERE settlement_key = ? AND street_key = ? "
                    "AND house_number_key = ? AND is_active = 1",
                    (normkey(r["settlement"]), st_key,
                     norm_house_number(r["house_number"])),
                ).fetchall()
                address_identity_mode = "UNIQUE_MUNICIPALITY_ADDRESS_FALLBACK"
            municipality_ids = {row[2] for row in candidate_rows if row[2]}
            if candidate_rows and spread_metres(candidate_rows) <= MAX_ADDRESS_SPREAD_M:
                if address_identity_mode == "MUNICIPALITY_ADDRESS" or len(municipality_ids) == 1:
                    rows = candidate_rows
                    identity_mode = address_identity_mode
                    tier, note = "ADDRESS_POINT", f"{len(rows)} exact address point(s)"
                else:
                    ambiguity["ADDRESS_POINT_REJECTED"] += 1
            elif candidate_rows:
                ambiguity["ADDRESS_POINT_REJECTED"] += 1

        if (tier == "NONE" and municipality_key and r["settlement"]
                and r["street"]):
            st_key = normkey(r["street"])
            rows = q("SELECT lon, lat FROM address_point "
                     "WHERE municipality_key = ? AND settlement_key = ? "
                     "AND street_key = ? AND is_active = 1",
                     (municipality_key, normkey(r["settlement"]), st_key)).fetchall()
            street_identity_mode = "MUNICIPALITY_STREET"
            if not rows:
                candidate_rows = q(
                    "SELECT lon, lat, municipality_key FROM address_point "
                    "WHERE settlement_key = ? AND street_key = ? AND is_active = 1",
                    (normkey(r["settlement"]), st_key),
                ).fetchall()
                municipality_ids = {row[2] for row in candidate_rows if row[2]}
                if len(municipality_ids) == 1:
                    rows = candidate_rows
                    street_identity_mode = "UNIQUE_MUNICIPALITY_STREET_FALLBACK"
            if rows:
                identity_mode = street_identity_mode
                tier, note = "STREET", f"{len(rows)} point(s) on street"

        if tier == "NONE" and municipality_key and ko_key:
            rows = q("SELECT lon, lat FROM address_point "
                     "WHERE municipality_key = ? AND ko_key = ? AND is_active = 1",
                     (municipality_key, ko_key)).fetchall()
            ko_identity_mode = "MUNICIPALITY_KO_NAME"
            if not rows:
                candidate_rows = q(
                    "SELECT lon, lat, ko_id FROM address_point "
                    "WHERE ko_key = ? AND is_active = 1",
                    (ko_key,),
                ).fetchall()
                ko_ids = {row[2] for row in candidate_rows if row[2]}
                if len(ko_ids) == 1:
                    rows = candidate_rows
                    ko_identity_mode = "UNIQUE_KO_ID_FALLBACK"
            if rows:
                identity_mode = ko_identity_mode
                tier, note = "KO_CENTROID", f"mean of {len(rows)} KO point(s)"

        if tier == "NONE" and municipality_key and r["settlement"]:
            rows = q("SELECT lon, lat FROM address_point "
                     "WHERE municipality_key = ? AND settlement_key = ? "
                     "AND is_active = 1",
                     (municipality_key, normkey(r["settlement"]))).fetchall()
            if rows:
                identity_mode = "MUNICIPALITY_SETTLEMENT"
                tier, note = "SETTLEMENT_CENTROID", f"mean of {len(rows)} point(s)"

        if tier == "NONE" and municipality_key:
            rows = q("SELECT lon, lat FROM address_point "
                     "WHERE municipality_key = ? AND is_active = 1",
                     (municipality_key,)).fetchall()
            if rows:
                identity_mode = "MUNICIPALITY"
                tier, note = "MUNICIPALITY_CENTROID", f"mean of {len(rows)} point(s)"

        lon = lat = None
        point_selection = None
        if rows:
            if tier in ("PARCEL_JOIN", "ADDRESS_POINT"):
                lon, lat = medoid_point(rows)
                point_selection = "OBSERVED_POINT_NEAREST_CENTRE"
            else:
                lon, lat = mean_point(rows)
                point_selection = "CENTROID"

        tier_counts[tier] += 1
        results.append({
            **{k: r[k] for k in ("id", "url", "ko_field", "parcel", "street",
                                 "house_number", "municipality", "settlement",
                                 "category")},
            "parcels": parcels,
            "matched_parcel": matched_parcel,
            "tier": tier,
            "precision": PRECISION_OF[tier],
            "candidates": len(rows),
            "spread_m": spread_metres(rows),
            "note": note,
            "identity_mode": identity_mode,
            "point_selection": point_selection,
            "lon": lon,
            "lat": lat,
        })

    write_json(out("resolved.json"), results)

    total = len(results)
    parcel_attempts = [r for r in results if r["parcels"]]
    parcel_matches = [r for r in results if r["tier"] == "PARCEL_JOIN"]
    summary = {
        "total": total,
        "tiers": {t: {"count": tier_counts.get(t, 0),
                      "pct": round(100.0 * tier_counts.get(t, 0) / total, 1)}
                  for t in TIERS},
        "candidate_and_rejection_counts": dict(sorted(ambiguity.items())),
        "placed_any": sum(1 for x in results if x["lon"] is not None),
        "parcel_join_identity": {
            "auctions_with_parcel_refs": len(parcel_attempts),
            "zero_point_matches": len(parcel_attempts) - len(parcel_matches),
            "one_point_matches": sum(r["candidates"] == 1 for r in parcel_matches),
            "many_point_matches": sum(r["candidates"] > 1 for r in parcel_matches),
            "max_points_in_match": max(
                (r["candidates"] for r in parcel_matches), default=0
            ),
        },
        "thresholds_m": {
            "parcel_join_max_spread": MAX_PARCEL_SPREAD_M,
            "exact_address_max_spread": MAX_ADDRESS_SPREAD_M,
        },
    }
    write_json(out("resolution_stats.json"), summary)

    print(f"{'tier':24s} {'count':>6s} {'pct':>7s}")
    print("-" * 40)
    for t in TIERS:
        c = summary["tiers"][t]
        print(f"{t:24s} {c['count']:6d} {c['pct']:6.1f}%")
    print("-" * 40)
    print(f"{'placed (any tier)':24s} {summary['placed_any']:6d}")
    print(f"\nCandidate/rejection counts: "
          f"{summary['candidate_and_rejection_counts']}")
    db.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
