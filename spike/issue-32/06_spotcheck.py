#!/usr/bin/env python3
"""Stage 6 - draw a spot-check sample and render coordinate-bound verdicts.

The issue requires at least 20 placements checked individually against a
public map, with each verdict listed rather than summarized. A human records
those checks in spotcheck-verdicts.json; this script refuses stale coordinates
and renders the sheet. The sample is weighted toward the highest tiers because
those are the ones whose claimed precision can be wrong in an interesting way.

    python3 06_spotcheck.py --n 20 --seed 32
    python3 06_spotcheck.py --n 20 --seed 32 --reverse-osm
"""
import argparse
import json
import os
import random
import sys
import time
import urllib.parse
import urllib.request

from common import HERE, out, read_json, write_json

RGZ_VIEWER = "https://a3.geosrbija.rs/"
NOMINATIM = "https://nominatim.openstreetmap.org/reverse"
DEFAULT_VERDICTS = os.path.join(HERE, "spotcheck-verdicts.json")


def markdown_cell(value):
    """Keep API whitespace and pipes from breaking the generated table."""
    return " ".join(str(value or "-").split()).replace("|", "\\|")


def reverse_osm(row):
    query = urllib.parse.urlencode({
        "format": "jsonv2",
        "lat": row["lat"],
        "lon": row["lon"],
        "zoom": 18,
        "addressdetails": 1,
    })
    req = urllib.request.Request(
        f"{NOMINATIM}?{query}",
        headers={"User-Agent": "aukcije-core-spike-32/1.0 (GitHub issue 32)"},
    )
    with urllib.request.urlopen(req, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=20)
    ap.add_argument("--seed", type=int, default=32)
    ap.add_argument("--reverse-osm", action="store_true",
                    help="also capture independent public-map context at 1 req/s")
    ap.add_argument("--reverse-delay", type=float, default=1.1)
    ap.add_argument("--verdicts", default=DEFAULT_VERDICTS,
                    help="versioned manual verdict file; ignored when absent")
    args = ap.parse_args()

    resolved = [r for r in read_json(out("resolved.json")) if r["lon"] is not None]
    rng = random.Random(args.seed)

    # Oversample the precise tiers; a KO centroid being "in the right place"
    # is not an interesting fact.
    priority = {"PARCEL_JOIN": 0, "ADDRESS_POINT": 0, "STREET": 1}
    buckets = {}
    for r in resolved:
        buckets.setdefault(priority.get(r["tier"], 2), []).append(r)

    sample = []
    for key in sorted(buckets):
        rows = buckets[key][:]
        rng.shuffle(rows)
        sample.extend(rows[: max(0, args.n - len(sample))])
        if len(sample) >= args.n:
            break

    contexts = []
    if args.reverse_osm:
        for i, row in enumerate(sample, 1):
            print(f"reverse public-map context {i}/{len(sample)}", file=sys.stderr)
            try:
                context = reverse_osm(row)
            except Exception as exc:  # preserve the other 19 checks on one failure
                context = {"error": str(exc)}
            contexts.append({
                "auction_id": row["id"],
                "lat": row["lat"],
                "lon": row["lon"],
                "display_name": context.get("display_name"),
                "address": context.get("address"),
                "error": context.get("error"),
            })
            if i < len(sample):
                time.sleep(max(1.0, args.reverse_delay))
        write_json(out("spotcheck_context.json"), contexts)

    verdict_meta, verdicts = {}, {}
    if args.verdicts and os.path.exists(args.verdicts):
        verdict_meta = read_json(args.verdicts)
        checks = verdict_meta.get("checks", [])
        verdicts = {int(check["auction_id"]): check for check in checks}
        if len(verdicts) != len(checks):
            raise RuntimeError("verdict file contains duplicate auction ids")
        allowed = {"correct", "near", "wrong"}
        for row in sample:
            check = verdicts.get(int(row["id"]))
            if not check:
                continue
            if check.get("verdict") not in allowed:
                raise RuntimeError(f"invalid verdict for auction {row['id']}")
            if (abs(float(check["lat"]) - row["lat"]) > 0.00001
                    or abs(float(check["lon"]) - row["lon"]) > 0.00001):
                raise RuntimeError(
                    f"stale verdict coordinates for auction {row['id']}; re-check it"
                )

    lines = [
        f"# Spot-check sheet - {len(sample)} placements",
        "",
        f"Check each `lat, lon` against {RGZ_VIEWER} (search the KO and parcel)",
        "or any public map. Replace the verdict column with one of:",
        "",
        "- `correct` - public-map context agrees with the expected KO/settlement/address",
        "- `near` - right municipality, wrong expected settlement/address",
        "- `wrong` - wrong municipality or worse",
        "",
        "This context check does not verify cadastral boundary geometry.",
        "",
        "| # | auction | tier | KO | parcel | lat, lon | cand | spread m | verdict | note |",
        "|---|---|---|---|---|---|---|---|---|---|",
    ]
    counts = {"correct": 0, "near": 0, "wrong": 0}
    for i, r in enumerate(sample, 1):
        check = verdicts.get(int(r["id"]), {})
        verdict = check.get("verdict", "TODO")
        note = markdown_cell(check.get("note", "")) if check else ""
        if verdict in counts:
            counts[verdict] += 1
        osm_url = (f"https://www.openstreetmap.org/?mlat={r['lat']:.6f}"
                   f"&mlon={r['lon']:.6f}#map=18/{r['lat']:.6f}/{r['lon']:.6f}")
        lines.append(
            f"| {i} | [{r['id']}]({r['url']}) | {r['tier']} | "
            f"{markdown_cell(r['ko_field'])} | {markdown_cell(r['parcel'])} | "
            f"[`{r['lat']:.5f}, {r['lon']:.5f}`]({osm_url}) | "
            f"{r['candidates']} | {r['spread_m']} | {verdict} | {note} |"
        )
    checked = sum(counts.values())
    lines += [
        "",
        (f"Totals: correct {counts['correct']} / {len(sample)}, "
         f"near {counts['near']}, wrong {counts['wrong']}; "
         f"checked {checked}/{len(sample)}"),
    ]
    if verdict_meta:
        lines.append(
            f"Checked {verdict_meta.get('checked_at')} against "
            f"{verdict_meta.get('public_map')}."
        )

    path = out("spotcheck_sheet.md")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    print(f"wrote {path} ({len(sample)} rows, {checked} recorded verdicts)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
